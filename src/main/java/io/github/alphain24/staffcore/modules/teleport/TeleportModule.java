package io.github.alphain24.staffcore.modules.teleport;

import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.module.Module;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Staff teleports, each of which remembers where you came from so {@code /back} works.
 * <p>
 * The return point stores a dimension key rather than a {@link ServerLevel} reference —
 * holding the level object alive across an unload would pin the whole dimension.
 */
public class TeleportModule implements Module {

	@Override
	public String id() {
		return "teleport";
	}

	@Override
	public String displayName() {
		return "Teleport";
	}

	private record Location(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

	private final Map<UUID, Location> lastPos = new HashMap<>();

	private final TeleportLog log = new TeleportLog();
	private boolean registered;

	/** Where players have teleported from and to. */
	public TeleportLog log() {
		return log;
	}

	@Override
	public void onEnable() {
		if (registered) return;
		registered = true;

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(log::tick);
		// A respawn is a teleport the game makes, so it is named rather than left as a jump.
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
				(old, fresh, alive) -> log.expect(fresh, TeleportLog.Cause.RESPAWN, null, null, null));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> log.forget(handler.getPlayer().getUUID()));
	}

	public void toPlayer(ServerPlayer staff, ServerPlayer target) {
		remember(staff);
		log.expect(staff, TeleportLog.Cause.STAFF_TO_PLAYER, Mc.name(staff), target.getUUID(),
				Mc.name(target));
		Mc.teleport(staff, target.level(), target.getX(), target.getY(), target.getZ(),
				target.getYRot(), target.getXRot());
		Sfx.teleport(staff);
	}

	public void bringHere(ServerPlayer staff, ServerPlayer target) {
		remember(target);
		log.expect(target, TeleportLog.Cause.STAFF_BRING, Mc.name(staff), staff.getUUID(),
				Mc.name(staff));
		Mc.teleport(target, staff.level(), staff.getX(), staff.getY(), staff.getZ(),
				staff.getYRot(), staff.getXRot());
		Sfx.teleport(target);
		Sfx.success(staff);
	}

	/** As below, into a given world — case evidence can be anywhere. */
	public void toPosition(ServerPlayer staff, net.minecraft.server.level.ServerLevel level,
			double x, double y, double z) {
		remember(staff);
		log.expect(staff, TeleportLog.Cause.STAFF_TO_PLACE, Mc.name(staff), null, null);
		Mc.teleport(staff, level, x, y, z, staff.getYRot(), staff.getXRot());
		Sfx.teleport(staff);
	}

	public void toPosition(ServerPlayer staff, double x, double y, double z) {
		remember(staff);
		log.expect(staff, TeleportLog.Cause.STAFF_TO_PLACE, Mc.name(staff), null, null);
		Mc.teleport(staff, staff.level(), x, y, z, staff.getYRot(), staff.getXRot());
		Sfx.teleport(staff);
	}

	/** Returns false when there is nowhere to go back to. */
	public boolean back(ServerPlayer staff) {
		Location prev = lastPos.remove(staff.getUUID());
		if (prev == null) return false;

		ServerLevel level = Mc.server(staff) == null ? null : Mc.server(staff).getLevel(prev.dimension());
		if (level == null) return false;

		log.expect(staff, TeleportLog.Cause.STAFF_BACK, Mc.name(staff), null, null);
		Mc.teleport(staff, level, prev.pos().x, prev.pos().y, prev.pos().z, prev.yaw(), prev.pitch());
		Sfx.teleport(staff);
		return true;
	}

	public boolean hasReturnPoint(ServerPlayer staff) {
		return lastPos.containsKey(staff.getUUID());
	}

	public void forget(UUID player) {
		lastPos.remove(player);
	}

	private void remember(ServerPlayer p) {
		lastPos.put(p.getUUID(), new Location(
				p.level().dimension(), p.position(), p.getYRot(), p.getXRot()));
	}
}
