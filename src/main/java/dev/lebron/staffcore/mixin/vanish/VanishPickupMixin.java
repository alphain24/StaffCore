package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Experience orbs ignore vanished players.
 * <p>
 * Orbs visibly stream toward whoever is collecting them, so a vanished staff member walking
 * past a mob farm drags a comet tail of xp across the screen of everyone watching. Items
 * and arrows are handled by their own mixins for the same reason.
 */
@Mixin(ExperienceOrb.class)
public class VanishPickupMixin {

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void staffcore$noOrbPickup(Player player, CallbackInfo ci) {
		if (VanishHooks.intangible(player)) ci.cancel();
	}
}
