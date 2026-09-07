package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanish, answered through the question vanilla already asks.
 * <p>
 * {@code Entity#broadcastToPlayer} is the server's own "should this viewer be told I
 * exist?" hook. {@code ChunkMap$TrackedEntity#updatePlayer} calls it as part of deciding
 * whether a viewer is in range, and — this is the part that matters — the {@code else}
 * branch of that decision calls {@code removePlayer}, which sends the removal packet
 * <em>and</em> takes the viewer out of {@code seenBy}.
 * <p>
 * <b>Why this replaces the old approach.</b> StaffCore used to cancel {@code updatePlayer}
 * outright and send removal packets by hand. Those packets told the client to forget the
 * entity, but nothing told the <em>server</em>: the viewer stayed in {@code seenBy}, and
 * because the method was cancelled at its head, vanilla's own removal branch never ran to
 * correct it. On un-vanish the tracker looked at {@code seenBy}, concluded the viewer could
 * already see the player, and sent no spawn packet — so the staff member stayed invisible
 * until they walked out of tracking range and back. That is the "I un-vanished and they
 * still can't see me" bug, and it was not intermittent; it was every time neither player
 * moved far enough.
 * <p>
 * Answering the question instead of suppressing it makes both directions vanilla's problem:
 * hiding tears the pairing down through the supported path, and revealing re-pairs and
 * re-spawns on the next tick with no help from us. It also replaces a mixin that had to
 * target a package-private inner class by string name with one on a stable public method.
 * <p>
 * Runs for every tracked entity/viewer pair every tick, so the guard is ordered to fail on
 * the cheapest test first.
 */
@Mixin(Entity.class)
public class VanishBroadcastMixin {

	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void staffcore$hideFromTracker(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (!(self instanceof ServerPlayer tracked) || tracked == viewer) return;

		if (VanishHooks.hiddenFrom(tracked, viewer)) {
			cir.setReturnValue(false);
		}
	}
}
