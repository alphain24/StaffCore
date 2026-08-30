package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mobs never acquire a vanished player as a target.
 * <p>
 * The tick sweep that clears targets afterwards was always a repair rather than a fix —
 * between the mob acquiring and the sweep clearing, it turns, paths and swings. Refusing
 * the assignment means it never notices at all.
 */
@Mixin(Mob.class)
public class VanishMobTargetMixin {

	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void staffcore$neverTargetVanished(LivingEntity target, CallbackInfo ci) {
		if (target != null && VanishHooks.ignoredByMobs(target)) ci.cancel();
	}
}
