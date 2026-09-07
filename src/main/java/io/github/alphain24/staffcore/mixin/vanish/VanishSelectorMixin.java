package io.github.alphain24.staffcore.mixin.vanish;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanished players fall out of {@code @a} and friends.
 * <p>
 * Command selectors are how a curious player finds out somebody is hiding: {@code /msg @r},
 * a scoreboard, anything iterating {@code @a}. Filtering here covers every command at once
 * rather than patching them individually.
 * <p>
 * Staff who can see through vanish keep the unfiltered result, so {@code /tp @a} still does
 * what an admin expects — and the console is never filtered, because automation that
 * silently skips players is far worse than automation that includes a hidden one.
 */
@Mixin(EntitySelector.class)
public class VanishSelectorMixin {

	@Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
	private void staffcore$hideVanished(CommandSourceStack source,
			CallbackInfoReturnable<List<ServerPlayer>> cir) throws CommandSyntaxException {

		List<ServerPlayer> found = cir.getReturnValue();
		if (found == null || found.isEmpty()) return;

		ServerPlayer asking = source.getPlayer();
		if (asking == null) return;                                   // console
		if (Permissions.check(asking, Nodes.VANISH)) return;          // may see them

		List<ServerPlayer> visible = new ArrayList<>(found.size());
		for (ServerPlayer player : found) {
			if (player == asking || !VanishHooks.hiddenFromLists(player)) visible.add(player);
		}
		if (visible.size() != found.size()) cir.setReturnValue(visible);
	}
}
