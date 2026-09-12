package io.github.alphain24.staffcore.gui.menu;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Gui;
import io.github.alphain24.staffcore.gui.Guis;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.modules.replay.PositionLog;
import io.github.alphain24.staffcore.modules.replay.SessionReplay;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

/**
 * Choosing how far back to watch somebody's session.
 *
 * <h2>Why the window is a screen rather than a number typed after a command</h2>
 * {@code /staff replay Bob 10m} works and requires knowing the syntax, the player's exact
 * name, and that 10m is even a thing you can write. The windows here are the ones anybody
 * actually asks for, and the screen can say — before you pick one — whether there is anything
 * recorded to watch at all.
 *
 * <h2>What it says when there is nothing</h2>
 * Position tracking is off by default, and switching it on does not fill in the past. So the
 * two ways to get an empty replay are completely different problems: "this server does not
 * record movement" and "this player was not online then". A command answers both with the same
 * shrug; this screen names which one it is, because the first has a fix and the second does not.
 */
public final class ReplayMenu extends Gui {

	private static final int TITLE = 4;

	/** The windows people ask for, in the order they ask for them. */
	private static final int[] SLOTS = {19, 20, 21, 22, 23};
	private static final String[] LABELS = {"5 minutes", "10 minutes", "30 minutes", "1 hour",
			"6 hours"};
	private static final long[] WINDOWS = {5L * 60_000, 10L * 60_000, 30L * 60_000,
			3_600_000L, 6L * 3_600_000L};

	private static final int BACK = 45;
	private static final int CLOSE = 49;

	private final NameAndId target;

	public static void open(ServerPlayer viewer, NameAndId target) {
		Guis.navigate(viewer, Theme.title("Replay", target.name()),
				(id, inv, v) -> new ReplayMenu(id, inv, v, target));
	}

	private ReplayMenu(int containerId, Inventory playerInventory, ServerPlayer viewer,
			NameAndId target) {

		super(containerId, playerInventory, viewer, 6);
		this.target = target;
		render();
	}

	@Override
	protected void build() {
		boolean tracking = StaffConfig.get().positionTracking;

		set(TITLE, Icon.of(Items.RECOVERY_COMPASS)
				.name("Replay " + target.name(), Theme.ACCENT)
				.lore("Watch where they went, from inside their own path.")
				.gap()
				.state(tracking, "Recording movement", "Position tracking is OFF")
				.build());

		if (!tracking) {
			// Said instead of the windows, not beside them. Offering a choice that cannot
			// work is worse than offering none, and the fix is a config key rather than a
			// different selection.
			set(22, Icon.of(Items.BARRIER)
					.name("Nothing is being recorded", Theme.BAD)
					.lore("positionTracking is false in config/staffcore.json.")
					.gap()
					.lore("Turning it on records from that point forward.", Theme.MUTED)
					.lore("It does not fill in the past, so this player's", Theme.MUTED)
					.lore("history before the change stays unavailable.", Theme.MUTED)
					.gap()
					.lore("It is off by default on purpose: a position row", Theme.MUTED)
					.lore("exists because a player existed, not because", Theme.MUTED)
					.lore("they did anything.", Theme.MUTED)
					.build());

			navigation();
			return;
		}

		long now = System.currentTimeMillis();
		for (int i = 0; i < SLOTS.length; i++) {
			long window = WINDOWS[i];
			PositionLog.Track track = PositionLog.reconstruct(target.id(), target.name(),
					now - window, now);

			boolean anything = !track.isEmpty();
			Icon icon = Icon.of(anything ? Items.CLOCK : Items.GLASS_PANE)
					.name(LABELS[i], anything ? Theme.ACCENT : Theme.MUTED)
					.lore(anything
							? "Watch the last " + LABELS[i].toLowerCase() + "."
							: "Nothing recorded in this window.");

			if (anything) {
				icon.gap()
						.field("Movement", io.github.alphain24.staffcore.util.TimeFormat
								.length(track.span()))
						.field("Stretches", String.valueOf(track.runs()));
				if (track.runs() > 1) {
					icon.lore("They stopped or relogged in between.", Theme.MUTED);
				}
			}

			int slot = SLOTS[i];
			if (!anything) {
				set(slot, icon.build());
				continue;
			}
			button(slot, icon.build(), click -> start(window));
		}

		navigation();
	}

	/** Starts the replay, or says why it could not. */
	private void start(long window) {
		MinecraftServer server = Mc.server(viewer);
		if (server == null) return;

		viewer.closeContainer();
		SessionReplay.Entry entry = SessionReplay.enter(server, viewer, target.id(),
				target.name(), null, window);

		if (!entry.started()) {
			viewer.sendSystemMessage(Theme.bad(entry.refusal()));
			Sfx.deny(viewer);
		}
	}

	private void navigation() {
		backButton(BACK, "the player list",
				() -> PlayerListMenu.open(viewer, PlayerListMenu.Purpose.REPLAY));

		button(CLOSE, Theme.closeButton(), click -> viewer.closeContainer());
		fillEmpty(Theme.filler());
	}
}
