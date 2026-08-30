package dev.lebron.staffcore.modules.freeze;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Module;
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

	/** Reminder cadence, in ticks. */
	private static final int NAG_INTERVAL = 200;

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
				if (tick % NAG_INTERVAL == 0) {
					p.sendSystemMessage(Theme.warn("You are frozen. Do not log out — talk to staff."));
				}
			}
		});
	}

	public boolean isFrozen(ServerPlayer player) {
		return anchors.containsKey(player.getUUID());
	}

	/** Returns the new state: true = now frozen. */
	public boolean toggle(ServerPlayer target) {
		if (anchors.remove(target.getUUID()) != null) {
			dev.lebron.staffcore.StaffCore.state().setFrozen(target.getUUID(), null, null);
			target.sendSystemMessage(Theme.good("You have been unfrozen."));
			Sfx.unfrozen(target);
			return false;
		}

		anchors.put(target.getUUID(), target.position());
		dev.lebron.staffcore.StaffCore.state().setFrozen(target.getUUID(),
				dev.lebron.staffcore.compat.Mc.dimensionId(target.level()), target.position());
		target.sendSystemMessage(Theme.bad("You have been frozen by staff."));
		target.sendSystemMessage(Theme.warn("Do not log out. Logging out now is treated as evading."));
		Sfx.frozen(target);
		return true;
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
		dev.lebron.staffcore.StaffCore.state().setFrozen(player, null, null);
	}

	/** Re-freezes somebody who logged out frozen, at the coordinates they were held at. */
	public void restoreOnJoin(ServerPlayer player) {
		var stored = dev.lebron.staffcore.StaffCore.state().loadAll().get(player.getUUID());
		if (stored == null || !stored.frozen() || stored.freezeAt() == null) return;

		anchors.put(player.getUUID(), stored.freezeAt());
		player.sendSystemMessage(Theme.bad("You are still frozen. Logging out does not clear it."));
		Sfx.frozen(player);
	}

	public int frozenCount() {
		return anchors.size();
	}
}
