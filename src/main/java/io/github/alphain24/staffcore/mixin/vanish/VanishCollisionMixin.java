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

}

/**
 * The pushability half, on {@link net.minecraft.world.entity.LivingEntity} rather than
 * {@code Entity}.
 * <p>
 * It was on {@code Entity} and it did nothing, because {@code LivingEntity} overrides
 * {@code isPushable()} — so for every player and every mob, the override won and the injection
 * was never reached. The mixin applied cleanly, the health check reported the target present,
 * and mobs walked into vanished staff regardless. That is the exact failure the health check
 * cannot see: a hook that attaches perfectly to a method nothing calls.
 * <p>
 * Found by a gametest asking a real player whether it was pushable while vanished, which is
 * the only question that would have caught it.
 */
@Mixin(net.minecraft.world.entity.LivingEntity.class)
class VanishLivingCollisionMixin {

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void staffcore$notPushable(CallbackInfoReturnable<Boolean> cir) {
		if (VanishHooks.intangible((Entity) (Object) this)) cir.setReturnValue(false);
	}
}
