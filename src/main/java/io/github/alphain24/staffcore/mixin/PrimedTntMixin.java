package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.grief.GriefModule;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes TNT that has been primed with nobody behind it.
 * <p>
 * Redstone, fire, and a blast from other unowned TNT all prime TNT with a null owner, so the
 * explosion that follows names nobody — and redstone is how TNT griefing is done, because it
 * lets the griefer be somewhere else when it goes off. Every one of those passes through this
 * constructor at the block it came from, which is where {@link GriefModule} remembers who put
 * a TNT block down.
 * <p>
 * Read only. The owner stays null: vanilla uses it for kill credit and for who a blast can
 * hurt, and changing either would be StaffCore altering gameplay to make its log tidier.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {

	@Inject(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V",
			at = @At("TAIL"))
	private void staffcore$rememberUnownedTnt(Level level, double x, double y, double z,
			LivingEntity owner, CallbackInfo ci) {

		if (owner != null || !(level instanceof ServerLevel serverLevel)) return;

		StaffCore.modules().get("grief", GriefModule.class).ifPresent(grief ->
				grief.onTntPrimed((PrimedTnt) (Object) this, serverLevel));
	}
}
