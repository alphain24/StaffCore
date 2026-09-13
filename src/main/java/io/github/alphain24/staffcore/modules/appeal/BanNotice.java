package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentModule;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The ban screen with working buttons on it: open the Discord, copy the appeal code.
 *
 * <h2>Why not just make the ban screen clickable</h2>
 * Because the client will not click it. The disconnect screen draws its reason with a text
 * widget whose click handler is never set — confirmed in the 26.2 client — so a link on it is
 * text to retype and a code on it is text to copy out by hand. No server can change that.
 * <p>
 * A dialog can. The client accepts one while a connection is still being set up — after it
 * has logged in, before it is placed in the world — and a dialog button can open a web page or
 * put text on the clipboard.
 *
 * <h2>Why it copies the old screen</h2>
 * The first version was a separate window with its own wording, followed by the ban screen —
 * two screens saying the same thing two ways, which read as a glitch. So the window now
 * <em>is</em> the ban screen: the client's own "Failed to connect to the server" title, the
 * same text in the same colours, and the buttons underneath it.
 * <p>
 * Something still follows it, and cannot be removed. Every way a connection ends — the server
 * closing it, the player's own Disconnect — lands on the client's disconnected screen; that is
 * in the client and no server reaches it. Pressing Back therefore closes with one short line
 * rather than the whole ban a second time. A window left to time out closes with the full
 * text, because whoever walked away from it has not read it.
 *
 * <h2>How that is done without loosening the ban</h2>
 * Vanilla asks "may this profile in?" twice: once at login, and again at the very end of setup,
 * before the player is created. The login gate answers both.
 * <ol>
 *   <li>At login, and only there, a ban is <em>deferred</em> instead of refused — see
 *   {@link #atLogin}, which is the only thing that can mark a check as the login one.</li>
 *   <li>During setup, after the registries and before the spawn is prepared, the notice is
 *   queued as a setup task that never completes on its own. Setup cannot reach the player's
 *   world while it is pending.</li>
 *   <li>When they press Leave or the time runs out, the ban is read again and they are
 *   disconnected with the ordinary ban screen.</li>
 *   <li>The end-of-setup check is untouched and still refuses. It is what actually keeps them
 *   out, so every piece above can fail — the dialog not showing, the task not queuing, the click
 *   not arriving — and the outcome is the plain ban screen, one step later than before.</li>
 * </ol>
 * The one thing that must hold is that the defer never escapes the login check. It is set in a
 * try/finally around that single call and cleared before anything else runs on the thread.
 */
public final class BanNotice {
	private BanNotice() {}

	/** What the Leave button sends back. Nothing else is listened for. */
	public static final Identifier LEAVE = Identifier.fromNamespaceAndPath("staffcore", "ban_notice/leave");

	static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("staffcore:ban_notice");

	/** Between login and setup is milliseconds. Anything older is a connection that died. */
	static final long PENDING_MS = 30_000L;

	/** About as wide as the old screen's text, which the client sizes to the window. */
	private static final int BODY_WIDTH = 340;
	/** The client's standard button width, as on the old screen. */
	private static final int BUTTON_WIDTH = 200;
	/** Two of these side by side come to about the width of one standard button row. */
	private static final int SIDE_BY_SIDE_WIDTH = 150;

	/** True only for the duration of the login-stage check; see {@link #atLogin}. */
	private static final ThreadLocal<Boolean> LOGIN_STAGE = ThreadLocal.withInitial(() -> false);

	/** A ban deferred at login, carried to setup, where the database must not be read. */
	private record Pending(Punishment ban, long at) {}

	/** Keyed by profile id. Removed when setup picks it up; stale ones are swept on insert. */
	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	/** Notices on screen right now, so a Leave click can find its own. */
	private static final Map<UUID, Notice> SHOWING = new ConcurrentHashMap<>();

	// ------------------------------------------------------------------ login stage

	/**
	 * Runs vanilla's login-stage "may they in?" check, marked as the login one.
	 * <p>
	 * The only place the mark is set. Cleared in {@code finally}, so a check that throws cannot
	 * leave it set for the end-of-setup check that runs later on the same thread — which is the
	 * check that must never be deferred.
	 */
	public static Component atLogin(Supplier<Component> check) {
		LOGIN_STAGE.set(true);
		try {
			return check.get();
		} finally {
			LOGIN_STAGE.remove();
		}
	}

	/**
	 * Asked by the login gate once it has found a ban.
	 *
	 * @return true to let the connection continue into setup, where the notice is shown and
	 *         the final check refuses; false to refuse here, as before
	 */
	public static boolean defer(UUID player, Punishment ban) {
		if (!LOGIN_STAGE.get()) return false;
		if (!StaffConfig.get().banAppealWindow) return false;
		if (!hasButtons(ban)) return false;

		long now = System.currentTimeMillis();
		PENDING.values().removeIf(p -> now - p.at() > PENDING_MS);
		PENDING.put(player, new Pending(ban, now));
		return true;
	}

	/** For tests: whether a deferred ban is waiting for this player's setup. */
	public static boolean isPending(UUID player) {
		return PENDING.containsKey(player);
	}

	// ------------------------------------------------------------------ setup stage

	/**
	 * Queues the notice if this connection's ban was deferred at login. Called from the
	 * setup-tasks mixin, after vanilla's optional tasks and before the spawn is prepared.
	 * <p>
	 * Runs on the network thread, so it reads nothing from the database: the ban it shows is
	 * the one the login check found a moment ago, and it is read again before anybody leaves.
	 */
	public static void queue(ServerConfigurationPacketListenerImpl setup,
			Consumer<ConfigurationTask> addTask) {

		UUID id = setup.getOwner().id();
		Pending pending = PENDING.remove(id);
		if (pending == null || System.currentTimeMillis() - pending.at() > PENDING_MS) return;

		long seconds = Math.clamp(StaffConfig.get().banAppealWindowSeconds, 15, 600);
		Notice notice = new Notice(setup, id, pending.ban(),
				System.currentTimeMillis() + seconds * 1000L);
		SHOWING.put(id, notice);
		addTask.accept(notice);
	}

	/** A dialog button came back. Only Leave means anything, and only for this connection. */
	public static void onClick(ServerConfigurationPacketListenerImpl setup, Identifier id) {
		if (!LEAVE.equals(id)) return;
		Notice notice = SHOWING.get(setup.getOwner().id());
		if (notice != null && notice.setup == setup) notice.leave = true;
	}

	/** The connection went away before the notice finished. */
	public static void forget(UUID player) {
		SHOWING.remove(player);
		PENDING.remove(player);
	}

	/**
	 * The notice as a setup task. It never reports itself done while the ban stands, so setup
	 * cannot move on to the player's world behind it.
	 */
	static final class Notice implements ConfigurationTask {
		private final ServerConfigurationPacketListenerImpl setup;
		private final UUID player;
		private final Punishment ban;
		private final long deadline;
		private volatile boolean leave;

		Notice(ServerConfigurationPacketListenerImpl setup, UUID player, Punishment ban,
				long deadline) {
			this.setup = setup;
			this.player = player;
			this.ban = ban;
			this.deadline = deadline;
		}

		@Override
		public void start(Consumer<Packet<?>> send) {
			send.accept(new ClientboundShowDialogPacket(Holder.direct(dialogFor(ban))));
		}

		/** Server thread. */
		@Override
		public boolean tick() {
			if (!leave && System.currentTimeMillis() < deadline) return false;
			SHOWING.remove(player, this);

			PunishmentModule punish = StaffCore.modules()
					.get("punishment", PunishmentModule.class).orElse(null);
			Punishment still = punish == null ? ban : punish.activeBan(player);

			if (still != null) {
				setup.disconnect(leave || punish == null ? afterLeaving(still)
						: punish.disconnectScreen(still));
				return false;
			}
			// Lifted while they were reading. Setup carries on, and the end-of-setup check —
			// which asks the database, not this task — lets them in.
			return true;
		}

		@Override
		public Type type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------------- dialog

	/** Whether there is anything to click. Without a link or a code the plain screen says it all. */
	static boolean hasButtons(Punishment ban) {
		return inviteLink(StaffConfig.get().discordInvite) != null || ban.isAppealable();
	}

	/**
	 * The window itself. Public so a test can put it through the real packet codec — a dialog
	 * the codec rejects is a protocol error on the client, which is a kick with no explanation.
	 */
	public static Dialog dialogFor(Punishment ban) {
		return dialogFor(ban, inviteLink(StaffConfig.get().discordInvite));
	}

	/** As above with the invite given, so a test can build the Discord button without config. */
	public static Dialog dialogFor(Punishment ban, URI invite) {
		PunishmentModule punish = StaffCore.modules()
				.get("punishment", PunishmentModule.class).orElse(null);
		// The ban screen's own text, unchanged, so this reads as that screen with buttons
		// rather than as a different message about the same ban.
		Component body = punish == null ? Component.literal(ban.reasonOr("No reason given"))
				: punish.disconnectScreen(ban);

		List<ActionButton> actions = new ArrayList<>();

		if (invite != null) {
			actions.add(button(Component.literal("Open our Discord"),
					"Opens " + invite + " in your browser", SIDE_BY_SIDE_WIDTH,
					new ClickEvent.OpenUrl(invite)));
		}
		if (ban.isAppealable()) {
			String code = AppealCode.display(ban.appealCode());
			actions.add(button(Component.literal("Copy appeal code"),
					"Puts " + code + " on your clipboard, to paste into your appeal",
					SIDE_BY_SIDE_WIDTH, new ClickEvent.CopyToClipboard(code)));
		}

		// The client's own word for it, in the player's own language, as on the old screen.
		ActionButton back = button(CommonComponents.GUI_BACK, "Back to the server list",
				BUTTON_WIDTH, new ClickEvent.Custom(LEAVE, Optional.empty()));

		CommonDialogData common = new CommonDialogData(
				// Translated by the client, so it is the same title the old screen had.
				Component.translatable("connect.failed"),
				Optional.empty(),
				true,
				// Not paused, and nothing closes on a click: copying the code should leave the
				// screen up so the Discord button is still there to press next.
				false,
				DialogAction.NONE,
				List.of(new PlainMessage(body, BODY_WIDTH)),
				List.of());

		// At least one column: the codec rejects zero, which the client would see as a kick.
		return new MultiActionDialog(common, actions, Optional.of(back), Math.max(1, actions.size()));
	}

	/**
	 * What the client's disconnected screen says after Back — which it shows whatever the
	 * server does. One line and the code, because they have just read the rest.
	 */
	public static Component afterLeaving(Punishment ban) {
		MutableComponent out = Icon.text("You are banned.", Theme.BAD);
		if (ban.isAppealable()) {
			out.append(Icon.text("\nAppeal code: " + AppealCode.display(ban.appealCode()),
					Theme.TEXT));
		}
		return out;
	}

	private static ActionButton button(Component label, String tooltip, int width,
			ClickEvent click) {
		return new ActionButton(
				new CommonButtonData(label, Optional.of(Component.literal(tooltip)), width),
				Optional.of(new StaticAction(click)));
	}

	/**
	 * The configured invite as a link a client will open, or {@code null} when it is not one.
	 * <p>
	 * "discord.gg/abc" is how people paste invites, and it is not a URL: without a scheme the
	 * client refuses to open it. So a bare host gets https, and anything that is still not an
	 * http(s) link with a host is treated as plain text rather than as a broken button.
	 */
	public static URI inviteLink(String invite) {
		if (invite == null || invite.isBlank()) return null;
		String trimmed = invite.trim();
		if (!trimmed.contains("://")) trimmed = "https://" + trimmed;
		try {
			URI uri = new URI(trimmed);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("https") && !scheme.equals("http")) return null;
			if (uri.getHost() == null || !uri.getHost().contains(".")) return null;
			return uri;
		} catch (java.net.URISyntaxException e) {
			return null;
		}
	}
}
