package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * The world does not know a vanished player is nearby.
 * <p>
 * {@code getNearestPlayer} is the choke point almost everything proximity-based runs
 * through — natural mob spawning, phantom spawning, mob AI deciding whether anyone is
 * close, sleeping-monster checks. Filtering here closes a whole family of leaks with one
 * injection rather than chasing each system separately.
 * <p>
 * The tell this removes is subtle but real: hostile mobs stop spawning within 24 blocks of
 * any player, so a vanished admin standing in a mob farm quietly shuts it off, and the
 * owner watching their rates drop knows exactly what that means.
 */
@Mixin(EntityGetter.class)
public interface VanishNearestPlayerMixin {

	@Inject(method = "getNearestPlayer(DDDDLjava/util/function/Predicate;)Lnet/minecraft/world/entity/player/Player;",
			at = @At("RETURN"), cancellable = true)
	private void staffcore$ignoreVanished(double x, double y, double z, double range,
			Predicate<Entity> predicate, CallbackInfoReturnable<Player> cir) {

		Player found = cir.getReturnValue();
		if (found != null && VanishHooks.ignoredByMobs(found)) {
			cir.setReturnValue(null);
		}
	}
}
