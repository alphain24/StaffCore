package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a vanished player off the locator bar.
 * <p>
 * The waypoint system added in 26.x broadcasts a direction and distance for every player to
 * every other player, which is a complete bypass of every other layer of vanish: the entity
 * is never sent, the tab list never mentions them, and the locator bar points straight at
 * them anyway.
 * <p>
 * {@code createConnection} is the single point where the server decides that one player
 * should start receiving another's waypoint, so refusing there covers the whole feature
 * without having to track the connections themselves.
 */
@Mixin(ServerWaypointManager.class)
public class VanishWaypointMixin {

	@Inject(method = "createConnection", at = @At("HEAD"), cancellable = true)
	private void staffcore$hideVanishedWaypoint(ServerPlayer receiver, WaypointTransmitter transmitter,
			CallbackInfo ci) {

		if (!(transmitter instanceof ServerPlayer hidden)) return;

		if (VanishHooks.hiddenFrom(hidden, receiver)) {
			ci.cancel();
		}
	}
}
