package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anything a vanished player launches is discarded rather than flown.
 * <p>
 * Arrows, fireworks, fishing bobbers and ender pearls all trace a visible line straight
 * back to whoever fired them, which is a more precise giveaway than being seen would be.
 * Removing the projectile on its first server tick is heavier-handed than hiding it, but
 * it also means the projectile cannot hit, break or trigger anything and leave a second
 * trail of evidence.
 */
@Mixin(Projectile.class)
public class VanishProjectileMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void staffcore$dropVanishedProjectiles(CallbackInfo ci) {
		Projectile self = (Projectile) (Object) this;
		if (self.level().isClientSide()) return;

		Entity owner = self.getOwner();
		if (owner != null && VanishHooks.intangible(owner)) {
			self.remove(Entity.RemovalReason.DISCARDED);
			ci.cancel();
		}
	}
}
