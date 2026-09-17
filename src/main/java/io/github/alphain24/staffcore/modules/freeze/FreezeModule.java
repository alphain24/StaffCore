package io.github.alphain24.staffcore.modules.freeze;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pins a player in place without needing a client mod.
 * <p>
 * Every tick a frozen player who has drifted more than a few centimetres from their
 * anchor is snapped back. The tolerance matters: snapping on any movement at all fights
 * with the client's own prediction and produces a rubber-banding mess, whereas a small
 * dead zone reads as "you cannot walk" and stays smooth.
 * <p>
 * What the player is told is on their screen rather than in chat: see {@link FreezeScreen}.
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

	private final Map<UUID, Vec3> anchors = new HashMap<>();
	private int tick;
	private boolean listenerRegistered;

	@Override
	public void onEnable() {
		if (listenerRegistered) return;
		listenerRegistered = true;

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (anchors.isEmpty()) return;
			tick++;

			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Vec3 anchor = anchors.get(p.getUUID());
				if (anchor == null) continue;

				if (p.position().distanceToSqr(anchor) > SLACK_SQR) {
					Mc.teleport(p, p.level(), anchor.x, anchor.y, anchor.z, p.getYRot(), p.getXRot());
				}
				if (tick % BLIND_EVERY == 0) FreezeScreen.blind(p);
				if (tick % FreezeScreen.TITLE_EVERY_TICKS == 0) FreezeScreen.show(p, invite());
			}
		});
	}

	public boolean isFrozen(ServerPlayer player) {
		return anchors.containsKey(player.getUUID());
	}

	/** Returns the new state: true = now frozen. */
	public boolean toggle(ServerPlayer target) {
		if (anchors.remove(target.getUUID()) != null) {
			io.github.alphain24.staffcore.StaffCore.state().setFrozen(target.getUUID(), null, null);
			FreezeScreen.clear(target);
			target.sendSystemMessage(Theme.good("You have been unfrozen."));
			Sfx.unfrozen(target);
			return false;
		}

		anchors.put(target.getUUID(), target.position());
		io.github.alphain24.staffcore.StaffCore.state().setFrozen(target.getUUID(),
				io.github.alphain24.staffcore.compat.Mc.dimensionId(target.level()), target.position());
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

	/**
	 * Drops the in-memory anchor on disconnect but leaves the stored one alone — logging
	 * out is the most common way to dodge a freeze, so it must not be a way to clear one.
	 */
	public void onPlayerLeft(UUID player) {
		anchors.remove(player);
	}

	/** Clears a freeze entirely, memory and disk. */
	public void forget(UUID player) {
		anchors.remove(player);
		io.github.alphain24.staffcore.StaffCore.state().setFrozen(player, null, null);
	}

	/** Re-freezes somebody who logged out frozen, at the coordinates they were held at. */
	public void restoreOnJoin(ServerPlayer player) {
		var stored = io.github.alphain24.staffcore.StaffCore.state().loadAll().get(player.getUUID());
		if (stored == null || !stored.frozen() || stored.freezeAt() == null) return;

		anchors.put(player.getUUID(), stored.freezeAt());
		cover(player);
		Sfx.frozen(player);
	}

	public int frozenCount() {
		return anchors.size();
	}
}
