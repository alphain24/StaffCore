package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanished player makes no noise.
 * <p>
 * Footsteps, block breaking, eating, opening a door — every one of these is broadcast to
 * everybody nearby with the acting entity attached, and each is a giveaway that something
 * invisible is standing there. Suppressing at {@code Level#playSound} catches all of them
 * at once, rather than chasing each sound-producing action separately.
 */
@Mixin(Level.class)
public class VanishSoundMixin {

	@Inject(method = "playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void staffcore$silenceAtBlock(Entity source, BlockPos pos, SoundEvent sound,
			SoundSource category, float volume, float pitch, CallbackInfo ci) {
		if (VanishHooks.silent(source)) ci.cancel();
	}

	@Inject(method = "playSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void staffcore$silenceAtPoint(Entity source, double x, double y, double z,
			SoundEvent sound, SoundSource category, float volume, float pitch, CallbackInfo ci) {
		if (VanishHooks.silent(source)) ci.cancel();
	}
}
