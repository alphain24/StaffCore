package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sweeping edge does not splash onto a vanished player.
 * <p>
 * Staff on duty are already invulnerable, so this is not about the damage — it is about the
 * particles and the hit sound, which appear in mid-air next to whoever is swinging and give
 * the position away precisely.
 */
@Mixin(Player.class)
public class VanishSweepMixin {

	@Inject(method = "doSweepAttack", at = @At("HEAD"), cancellable = true)
	private void staffcore$noSweepOnVanished(Entity target, float damage, DamageSource source,
			float knockback, CallbackInfo ci) {
		if (VanishHooks.intangible(target)) ci.cancel();
	}
}
