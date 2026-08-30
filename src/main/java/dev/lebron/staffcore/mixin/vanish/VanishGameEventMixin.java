package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanished player emits no vibrations.
 * <p>
 * Game events are what sculk sensors, shriekers and the warden listen to, and they are
 * emitted for walking, breaking, opening and almost everything else. Without this, a
 * vanished admin crossing a sculk field lights it up like a runway.
 */
@Mixin(Level.class)
public class VanishGameEventMixin {

	@Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
			at = @At("HEAD"), cancellable = true)
	private void staffcore$noVibrations(Holder<GameEvent> event, Vec3 position,
			GameEvent.Context context, CallbackInfo ci) {
		if (context == null) return;
		Entity source = context.sourceEntity();
		if (source != null && VanishHooks.intangible(source)) ci.cancel();
	}
}
