package io.github.alphain24.staffcore.modules.freeze;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pins a player in place without needing a client mod.
 * <p>
 * Every tick a frozen player who has drifted more than a few centimetres from their
 * anchor is snapped back. The tolerance matters: snapping on any movement at all fights
 * with the client's own prediction and produces a rubber-banding mess, whereas a small
 * dead zone reads as "you cannot walk" and stays smooth.
 * <p>
 * What the player is told is on their screen rather than in chat: see {@link FreezeScreen}.
 * Leaving while frozen is reported to staff and added to the player's case: see
 * {@link #onDisconnect}.
 */
public class FreezeModule implements Module {

	@Override
	public String id() {
		return "freeze";
	}

	@Override
	public String displayName() {
		return "Freeze";
	}

	/** Squared distance a frozen player may drift before we pull them back. */
	private static final double SLACK_SQR = 0.04D;

	/** How often the blindness is topped up, in ticks. */
	private static final int BLIND_EVERY = 20;

	/** How sure "they left while frozen" is: certain it happened, and worth a case. */
	static final int EVASION_CONFIDENCE = 90;

	/** How far back the replay filed with an evasion reaches, at most. */
	private static final long REPLAY_BEFORE_MILLIS = 10 * 60_000L;

	/**
	 * One frozen player: where they are held, who froze them and when.
	 *
	 * @param by the staff member's name, or null when that is not known — after a restart, or for a
	 *           freeze a caller did not attribute
	 */
	public record Hold(Vec3 anchor, String dimension, String by, long since) {}

	private final Map<UUID, Hold> holds = new HashMap<>();
	/** Frozen players the server itself is disconnecting, with why; read once they are gone. */
	private final Map<UUID, Component> removedByServer = new ConcurrentHashMap<>();
	private int tick;
	private boolean listenerRegistered;

	@Override
	public void onEnable() {
		if (listenerRegistered) return;
		listenerRegistered = true;

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (holds.isEmpty()) return;
			tick++;

			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Hold hold = holds.get(p.getUUID());
				if (hold == null) continue;

				Vec3 anchor = hold.anchor();
				if (p.position().distanceToSqr(anchor) > SLACK_SQR) {
					Mc.teleport(p, p.level(), anchor.x, anchor.y, anchor.z, p.getYRot(), p.getXRot());
				}
				if (tick % BLIND_EVERY == 0) FreezeScreen.blind(p);
				if (tick % FreezeScreen.TITLE_EVERY_TICKS == 0) FreezeScreen.show(p, invite());
			}
		});
	}

	public boolean isFrozen(ServerPlayer player) {
		return holds.containsKey(player.getUUID());
	}

	/** Who holds this player, or null when they are not frozen or not online. */
	public Hold hold(UUID player) {
		return holds.get(player);
	}

	/** Returns the new state: true = now frozen. */
	public boolean toggle(ServerPlayer target) {
		return toggle(target, null);
	}

	/**
	 * Freezes or releases.
	 *
	 * @param by who is doing it, for the report if the player leaves while frozen
	 * @return the new state: true = now frozen
	 */
	public boolean toggle(ServerPlayer target, String by) {
		if (holds.remove(target.getUUID()) != null) {
			io.github.alphain24.staffcore.StaffCore.state().setFrozen(target.getUUID(), null, null);
			FreezeScreen.clear(target);
			target.sendSystemMessage(Theme.good("You have been unfrozen."));
			Sfx.unfrozen(target);
			return false;
		}

		String dimension = Mc.dimensionId(target.level());
		holds.put(target.getUUID(), new Hold(target.position(), dimension, by, System.currentTimeMillis()));
		io.github.alphain24.staffcore.StaffCore.state().setFrozen(target.getUUID(), dimension, target.position());
		cover(target);
		Sfx.frozen(target);
		return true;
	}

	/**
	 * The frozen screen, at once rather than at the next timer, and the Discord invite as the one line
	 * in chat — the only part of it a player can click.
	 */
	private static void cover(ServerPlayer target) {
		FreezeScreen.blind(target);
		FreezeScreen.show(target, invite());
		var line = FreezeScreen.inviteLine(invite());
		if (line != null) target.sendSystemMessage(line);
	}

	private static String invite() {
		return io.github.alphain24.staffcore.config.StaffConfig.get().discordInvite;
	}

	// ------------------------------------------------------------------ leaving while frozen

	/** Why a frozen player's connection ended. */
	public enum Ending {
		/** They left: the connection closed from their side. */
		LEFT(true),
		/** It timed out, which is also what pulling the network cable looks like. */
		LOST_CONNECTION(true),
		/** The server removed them: a kick, a ban, a login from elsewhere. Not their doing. */
		REMOVED_BY_SERVER(false),
		/** The server is stopping. */
		SERVER_STOPPING(false);

		private final boolean reported;

		Ending(boolean reported) {
			this.reported = reported;
		}

		/** Whether staff are told and it goes into the player's case. */
		public boolean reported() {
			return reported;
		}
	}

	/**
	 * Called by a mixin when the server itself disconnects a player, before the disconnect happens.
	 * Remembered only for players who are frozen, and only until {@link #onDisconnect} reads it.
	 */
	public void serverDisconnecting(UUID player, Component reason) {
		if (player != null && holds.containsKey(player)) {
			removedByServer.put(player, reason == null ? Component.empty() : reason);
		}
	}

	/**
	 * How a connection ended, from what the server said when it ended it, if it did.
	 *
	 * @param serverReason null when the server did not end it — the player left
	 */
	public static Ending ending(boolean serverRunning, Component serverReason) {
		String key = serverReason != null && serverReason.getContents() instanceof TranslatableContents translatable
				? translatable.getKey() : null;
		if (!serverRunning || "multiplayer.disconnect.server_shutdown".equals(key)) return Ending.SERVER_STOPPING;
		if (serverReason == null) return Ending.LEFT;
		if ("disconnect.timeout".equals(key)) return Ending.LOST_CONNECTION;
		return Ending.REMOVED_BY_SERVER;
	}

	/**
	 * A player disconnecting. Called before {@link #onPlayerLeft}. When they were frozen and left of their
	 * own accord, staff are told and it goes into the case they have open, or a new one.
	 *
	 * @return how it ended, or null when they were not frozen
	 */
	public Ending onDisconnect(MinecraftServer server, ServerPlayer player) {
		Component serverReason = removedByServer.remove(player.getUUID());
		Hold hold = holds.get(player.getUUID());
		if (hold == null) return null;

		Ending ending = ending(server != null && server.isRunning(), serverReason);
		if (ending.reported()) report(server, player, hold, ending);
		return ending;
	}

	private static void report(MinecraftServer server, ServerPlayer player, Hold hold, Ending ending) {
		String name = Mc.name(player);
		long now = System.currentTimeMillis();
		String detail = describe(name, hold, ending, now);

		java.util.List<io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft> drafts = new java.util.ArrayList<>();
		net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(hold.anchor());
		drafts.add(io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft.location(player.getUUID(), name,
				hold.dimension(), at, "Where " + name + " was frozen"));
		if (io.github.alphain24.staffcore.config.StaffConfig.get().positionTracking) {
			drafts.add(io.github.alphain24.staffcore.modules.cases.CaseEvidence.Draft.replay(player.getUUID(), name,
					hold.dimension(), at, Math.max(hold.since(), now - REPLAY_BEFORE_MILLIS) - 30_000L, now,
					"Frozen, until " + name + " left"));
		}
		io.github.alphain24.staffcore.module.Mods.cases().emit(server,
				io.github.alphain24.staffcore.modules.cases.Signal.Type.FREEZE_EVASION, player.getUUID(), name,
				EVASION_CONFIDENCE, detail, "freeze", drafts);
	}

	/** What staff are told: who, how, who froze them and how long ago. */
	static String describe(String name, Hold hold, Ending ending, long now) {
		String how = ending == Ending.LOST_CONNECTION ? " lost connection" : " left the server";
		String by = hold.by() == null || hold.by().isBlank() ? "" : " by " + hold.by();
		long held = Math.max(0, now - hold.since());
		String when = held < 60_000 ? "moments after being frozen"
				: "after " + io.github.alphain24.staffcore.util.TimeFormat.duration(held) + " frozen";
		return name + how + " while frozen" + by + ", " + when;
	}

	/**
	 * Drops the in-memory anchor on disconnect but leaves the stored one alone — logging
	 * out is the most common way to dodge a freeze, so it must not be a way to clear one.
	 */
	public void onPlayerLeft(UUID player) {
		holds.remove(player);
		removedByServer.remove(player);
	}

	/** Clears a freeze entirely, memory and disk. */
	public void forget(UUID player) {
		holds.remove(player);
		io.github.alphain24.staffcore.StaffCore.state().setFrozen(player, null, null);
	}

	/**
	 * Re-freezes somebody who logged out frozen, at the coordinates they were held at, and tells staff
	 * they are back — into the case their leaving went into, if it is still open.
	 */
	public void restoreOnJoin(ServerPlayer player) {
		var stored = io.github.alphain24.staffcore.StaffCore.state().loadAll().get(player.getUUID());
		if (stored == null || !stored.frozen() || stored.freezeAt() == null) return;

		holds.put(player.getUUID(), new Hold(stored.freezeAt(), stored.freezeWorld(), null,
				System.currentTimeMillis()));
		cover(player);
		Sfx.frozen(player);

		MinecraftServer server = player.level().getServer();
		String name = Mc.name(player);
		String line = name + " is back and still frozen";
		var open = io.github.alphain24.staffcore.module.Mods.cases().store().openCaseFor(player.getUUID());
		if (open.isPresent()) {
			io.github.alphain24.staffcore.module.Mods.cases().store().note(open.get().id(),
					io.github.alphain24.staffcore.modules.cases.Case.SYSTEM, "came back and is frozen again");
			line += " (case " + open.get().id() + ")";
		}
		if (server != null) io.github.alphain24.staffcore.module.Mods.alerts().onStaffAction(server, line);
	}

	public int frozenCount() {
		return holds.size();
	}
}
