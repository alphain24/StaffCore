package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing bumps into a vanished player.
 * <p>
 * Entity collision is the loudest physical tell there is: boats stop dead, minecarts refuse
 * to pass, mobs pile up against an invisible wall. Refusing both the push and the
 * pushability means the world flows through the space as though it were empty.
 */
@Mixin(Entity.class)
public class VanishCollisionMixin {

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void staffcore$noPush(Entity other, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (VanishHooks.intangible(self) || VanishHooks.intangible(other)) ci.cancel();
	}

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void staffcore$notPushable(CallbackInfoReturnable<Boolean> cir) {
		if (VanishHooks.intangible((Entity) (Object) this)) cir.setReturnValue(false);
	}
}
