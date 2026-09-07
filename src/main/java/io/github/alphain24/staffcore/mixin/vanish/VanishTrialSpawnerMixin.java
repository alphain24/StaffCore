package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Trial spawners and vaults do not notice a vanished player.
 * <p>
 * Both features detect nearby players to decide whether to activate and how hard to scale,
 * and a vault will not open for somebody it has not registered. Without this, walking a
 * vanished admin through a trial chamber arms the spawners and inflates the difficulty for
 * whoever is actually fighting there.
 * <p>
 * The detectors themselves are lambdas and effectively impossible to target, but every one
 * of them gathers players through {@code EntitySelector.SELECT_FROM_LEVEL}. Filtering that
 * single anonymous class covers trial spawners, ominous spawners and vaults together.
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.trialspawner.PlayerDetector$EntitySelector$1")
public class VanishTrialSpawnerMixin {

	@Inject(method = "getPlayers", at = @At("RETURN"), cancellable = true)
	private void staffcore$ignoreVanished(ServerLevel level, Predicate<? super Player> predicate,
			CallbackInfoReturnable<List<ServerPlayer>> cir) {

		List<ServerPlayer> found = cir.getReturnValue();
		if (found == null || found.isEmpty()) return;

		List<ServerPlayer> visible = new ArrayList<>(found.size());
		for (ServerPlayer player : found) {
			if (!VanishHooks.ignoredByMobs(player)) visible.add(player);
		}
		if (visible.size() != found.size()) cir.setReturnValue(visible);
	}
}
