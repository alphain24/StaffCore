package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Pressure plates do not click for somebody who is not there.
 * <p>
 * A plate depressing with nobody standing on it is about the most unambiguous tell vanish
 * has — it is visible, it is audible, and it opens the door the staff member was trying to
 * sneak past. The count is recomputed excluding vanished players rather than the predicate
 * being rewritten, because the predicate is a lambda and far more fragile to target.
 */
@Mixin(BasePressurePlateBlock.class)
public class VanishPressurePlateMixin {

	@Inject(method = "getEntityCount", at = @At("RETURN"), cancellable = true)
	private static void staffcore$ignoreVanished(Level level, AABB box, Class<? extends Entity> type,
			CallbackInfoReturnable<Integer> cir) {

		if (cir.getReturnValue() == 0) return;

		List<? extends Entity> inside = level.getEntitiesOfClass(type, box);
		int hidden = 0;
		for (Entity entity : inside) {
			if (VanishHooks.intangible(entity)) hidden++;
		}
		if (hidden > 0) {
			cir.setReturnValue(Math.max(0, cir.getReturnValue() - hidden));
		}
	}
}
