package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps vanished players out of the sleep count.
 * <p>
 * Otherwise a single hidden staff member silently raises the number of people who have to
 * be in bed before night passes, and the players left awake have no way to work out why.
 * <p>
 * The incoming list is rewritten rather than the method re-invoked with a filtered copy —
 * calling {@code update} again from inside {@code update} would recurse for ever.
 */
@Mixin(SleepStatus.class)
public class VanishSleepMixin {

	@ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
	private List<ServerPlayer> staffcore$excludeVanished(List<ServerPlayer> players) {
		if (players == null || players.isEmpty()) return players;

		boolean anyHidden = false;
		for (ServerPlayer player : players) {
			if (VanishHooks.hiddenFromLists(player)) {
				anyHidden = true;
				break;
			}
		}
		if (!anyHidden) return players;

		List<ServerPlayer> visible = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			if (!VanishHooks.hiddenFromLists(player)) visible.add(player);
		}
		return visible;
	}
}
