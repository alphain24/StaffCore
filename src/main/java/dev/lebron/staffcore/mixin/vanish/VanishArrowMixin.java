package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spent arrows and tridents are not collected by somebody who is not there. */
@Mixin(AbstractArrow.class)
public class VanishArrowMixin {

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void staffcore$noArrowPickup(Player player, CallbackInfo ci) {
		if (VanishHooks.intangible(player)) ci.cancel();
	}
}
