package io.github.alphain24.staffcore.modules.control;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.modules.alerts.AlertsModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Server-wide switches: chat lock, maintenance mode, command spy, broadcasts, TPS watch. */
public class ControlModule implements Module {

	@Override
	public String id() {
		return "control";
	}

	@Override
	public String displayName() {
		return "Server Control";
	}

	/** How often the TPS watchdog samples, in ticks. */
	private static final int TPS_SAMPLE_INTERVAL = 600;

	private boolean chatMuted;
	private boolean maintenance;
	/** The server's real MOTD, held while the maintenance one is showing. */
	private String savedMotd;
	private final Set<UUID> spies = new HashSet<>();

	private int tick;
	private boolean alertedLowTps;
	private boolean listenerRegistered;

	@Override
	public void onEnable() {
		if (listenerRegistered) return;
		listenerRegistered = true;

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tick % TPS_SAMPLE_INTERVAL != 0) return;

			double tps = currentTps(server);
			double floor = StaffConfig.get().tpsAlertFloor;

			// Edge-triggered: fire once on the way down, and once when it recovers.
			// A watchdog that repeats every sample is a watchdog people turn off.
			if (tps < floor && !alertedLowTps) {
				alertedLowTps = true;
				alert(server, "TPS dropped to %.1f (floor %.1f)".formatted(tps, floor));
			} else if (tps >= floor && alertedLowTps) {
				alertedLowTps = false;
				alert(server, "TPS recovered to %.1f".formatted(tps));
			}
		});
	}

	private void alert(MinecraftServer server, String detail) {
		StaffCore.modules().get("alerts", AlertsModule.class)
				.ifPresent(a -> a.onPerformance(server, detail));
	}

	// ------------------------------------------------------------------- switches

	public boolean isChatMuted() {
		return chatMuted;
	}

	public boolean isMaintenance() {
		return maintenance;
	}

	public boolean isSpy(UUID uuid) {
		return spies.contains(uuid);
	}

	public boolean toggleSpy(ServerPlayer staff) {
		if (spies.remove(staff.getUUID())) {
			Sfx.toggleOff(staff);
			return false;
		}
		spies.add(staff.getUUID());
		Sfx.toggleOn(staff);
		return true;
	}

	public void forget(UUID player) {
		spies.remove(player);
	}

	public boolean setChatMuted(MinecraftServer server, boolean muted) {
		chatMuted = muted;
		server.getPlayerList().broadcastSystemMessage(muted
				? Theme.warn("Chat has been locked by staff.")
				: Theme.good("Chat is open again."), false);
		return chatMuted;
	}

	public boolean toggleChatMute(MinecraftServer server) {
		return setChatMuted(server, !chatMuted);
	}

	public boolean toggleMaintenance(MinecraftServer server) {
		return setMaintenance(server, !maintenance, true);
	}

	/**
	 * Sets maintenance mode, and optionally clears the server of everybody who is not staff.
	 *
	 * <h2>Why the two halves are separable</h2>
	 * Closing the door and emptying the room are different decisions. Turning maintenance on
	 * always stops new logins — that is what the flag means, and the login gate reads it — but
	 * disconnecting the people already here is a choice about them rather than about the door.
	 * An admin who wants to stop new arrivals while finishing a conversation with whoever is
	 * online wants the first without the second.
	 * <p>
	 * {@link #toggleMaintenance} is the command path and does both, which is the behaviour the
	 * server has always had.
	 *
	 * @param disconnectPlayers whether to remove non-staff who are already connected
	 * @return the new state
	 */
	public boolean setMaintenance(MinecraftServer server, boolean on,
			boolean disconnectPlayers) {

		maintenance = on;
		applyMotd(server);

		if (maintenance && !disconnectPlayers) {
			StaffCore.LOGGER.info("[StaffCore] Maintenance mode on; existing players left "
					+ "connected");
			return true;
		}

		if (maintenance) {
			// Announce before kicking, so anyone who stays (staff) sees why the server
			// suddenly emptied, and anyone leaving has a moment to read it.
			server.getPlayerList().broadcastSystemMessage(
					Theme.warn("Maintenance mode on — the server is closing to players."), false);
			int removed = kickNonStaff(server);
			StaffCore.LOGGER.info("[StaffCore] Maintenance mode on; disconnected {} player(s)", removed);
		} else {
			server.getPlayerList().broadcastSystemMessage(
					Theme.good("Maintenance mode off — the server is open again."), false);
		}
		return maintenance;
	}

	/**
	 * Disconnects everybody without {@link Nodes#MAINTENANCE}.
	 * <p>
	 * Blocking new logins alone was half a feature: the players already on stayed on, and
	 * whatever you turned maintenance on to fix was still being played through. The message
	 * mirrors the login screen, so somebody kicked and somebody refused at the door read the
	 * same explanation rather than two different ones.
	 *
	 * @return how many were disconnected
	 */
	/**
	 * Who the kick would remove, without removing them.
	 * <p>
	 * Split out from the kick itself because the selection is where a mistake would live and
	 * the disconnect is not. Getting this wrong either empties the server of the staff who
	 * turned maintenance on, or kicks nobody and leaves the thing you closed the server to fix
	 * still being played through — and neither is visible from reading the loop.
	 * <p>
	 * Takes the players rather than the server so it can be asked about a set that is not the
	 * live player list, which is the only way to test it.
	 */
	public static List<ServerPlayer> kickTargets(Collection<ServerPlayer> players) {
		List<ServerPlayer> targets = new java.util.ArrayList<>();
		for (ServerPlayer player : players) {
			if (Permissions.check(player, Nodes.MAINTENANCE)) continue;
			targets.add(player);
		}
		return targets;
	}

	public int kickNonStaff(MinecraftServer server) {
		Component screen = maintenanceScreen();
		int removed = 0;

		// Copied by kickTargets, because disconnecting mutates the list we would be iterating.
		for (ServerPlayer player : kickTargets(server.getPlayerList().getPlayers())) {
			Mc.disconnect(player, screen);
			removed++;
		}
		return removed;
	}

	/** The same wording the login gate shows, so both routes explain it identically. */
	public static Component maintenanceScreen() {
		MutableComponent out = Icon.text("Maintenance\n\n", Theme.WARN);
		out.append(Icon.text("The server is closed while staff work on it.\n", Theme.TEXT));

		String invite = StaffConfig.get().discordInvite;
		if (invite != null && !invite.isBlank()) {
			out.append(Icon.text("\nUpdates are posted on our Discord:\n", Theme.MUTED));
			out.append(Icon.text(invite, Theme.ACCENT));
		} else {
			out.append(Icon.text("Please try again shortly.", Theme.MUTED));
		}
		return out;
	}

	/**
	 * Swaps the server-list MOTD while maintenance is on, and puts the original back after.
	 * <p>
	 * The point of maintenance mode is that people stop trying to join, and a refusal at
	 * the login screen only reaches players who already clicked connect. The MOTD is what
	 * they actually read. The original is captured the first time we replace it, so
	 * repeated toggles never leave the maintenance text stuck on.
	 */
	private void applyMotd(MinecraftServer server) {
		if (maintenance) {
			if (savedMotd == null) {
				savedMotd = server.getMotd();
			}
			server.setMotd(StaffConfig.get().maintenanceMotd);
		} else if (savedMotd != null) {
			server.setMotd(savedMotd);
			savedMotd = null;
		}
		// The status is cached between pings, so it has to be thrown away by hand.
		server.invalidateStatus();
	}

	/**
	 * Clears maintenance state on shutdown. Nothing needs restoring on disk: {@code setMotd}
	 * only changes the running value, so a crash mid-maintenance heals itself — the next
	 * start reads the real MOTD back out of {@code server.properties}.
	 */
	@Override
	public void onDisable() {
		maintenance = false;
		savedMotd = null;
	}

	// ------------------------------------------------------------------- messages

	/**
	 * Pushes the visible chat history off screen.
	 * <p>
	 * Staff are exempt: the whole reason to clear chat is that something was said that
	 * shouldn't have been, and staff usually still need to read it.
	 */
	public void clearChat(MinecraftServer server, String by) {
		Component blank = Component.literal(" ");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isSpy(p.getUUID())) continue;
			for (int i = 0; i < 100; i++) {
				p.sendSystemMessage(blank);
			}
			p.sendSystemMessage(Theme.info("Chat was cleared by " + by + "."));
		}
	}

	public void broadcast(MinecraftServer server, String message) {
		Component line = Icon.text("» ", Theme.ACCENT)
				.append(Icon.text(message, Theme.TEXT));
		server.getPlayerList().broadcastSystemMessage(line, false);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Sfx.alertPing(p);
		}
	}

	/** Fans an executed command out to everyone with spy on. */
	public void reportCommand(MinecraftServer server, String sender, String command) {
		if (spies.isEmpty()) return;

		Component line = Theme.prefix()
				.append(Icon.text("Spy ", Theme.MUTED))
				.append(Icon.text(sender, Theme.ACCENT))
				.append(Icon.text(": " + command, Theme.MUTED));

		for (UUID uuid : spies) {
			ServerPlayer p = server.getPlayerList().getPlayer(uuid);
			// Don't echo a spy's own commands back at them.
			if (p != null && !Mc.name(p).equals(sender)) {
				p.sendSystemMessage(line);
			}
		}
	}

	// ------------------------------------------------------------------------ tps

	/**
	 * Ticks per second derived from the mean tick time. A server keeping up spends less
	 * than 50 ms per tick, which is capped back to 20 rather than reported as 30+.
	 */
	public double currentTps(MinecraftServer server) {
		double meanMs = Mc.meanTickMs(server);
		if (meanMs <= 0) return 20.0D;
		return Math.min(20.0D, 1000.0D / meanMs);
	}

	public int tpsColor(double tps) {
		if (tps >= 19.0D) return Theme.GOOD;
		if (tps >= StaffConfig.get().tpsAlertFloor) return Theme.WARN;
		return Theme.BAD;
	}
}
