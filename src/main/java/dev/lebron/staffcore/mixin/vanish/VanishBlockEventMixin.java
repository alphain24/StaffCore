package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chests do not swing open for a vanished player.
 * <p>
 * The lid animation and its accompanying sound are broadcast to everybody in range, so
 * looking inside a chest while vanished is one of the most obvious remaining tells. The
 * sound is already suppressed elsewhere; this stops the lid.
 */
@Mixin(ChestBlockEntity.class)
public class VanishBlockEventMixin {

	@Inject(method = "startOpen", at = @At("HEAD"), cancellable = true)
	private void staffcore$silentOpen(ContainerUser user, CallbackInfo ci) {
		if (user instanceof net.minecraft.world.entity.Entity entity && VanishHooks.intangible(entity)) {
			ci.cancel();
		}
	}

	@Inject(method = "stopOpen", at = @At("HEAD"), cancellable = true)
	private void staffcore$silentClose(ContainerUser user, CallbackInfo ci) {
		if (user instanceof net.minecraft.world.entity.Entity entity && VanishHooks.intangible(entity)) {
			ci.cancel();
		}
	}
}
