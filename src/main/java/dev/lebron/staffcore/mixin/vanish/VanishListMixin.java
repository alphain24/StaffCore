package dev.lebron.staffcore.mixin.vanish;

import dev.lebron.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Hides vanished players from head counts.
 * <p>
 * {@code getPlayerCount} feeds the server list, the "x/y players" line and several plugins,
 * so a vanished admin who still bumps the number is only half hidden — anybody watching the
 * count knows somebody is on. The player <em>list</em> itself is deliberately left alone:
 * the server iterates it constantly for ticking, saving and broadcasting, and filtering it
 * globally would break far more than it hides.
 * <p>
 * Which left {@code /list} reading the unfiltered list and printing the name of somebody the
 * count had just been adjusted to hide — the count said four, the names said five. That is
 * handled below, at the one call site that wants a filtered view.
 */
@Mixin(PlayerList.class)
public class VanishListMixin {

	@Inject(method = "getPlayerCount", at = @At("RETURN"), cancellable = true)
	private void staffcore$hideFromCount(CallbackInfoReturnable<Integer> cir) {
		PlayerList self = (PlayerList) (Object) this;

		int hidden = 0;
		List<ServerPlayer> players = self.getPlayers();
		for (int i = 0; i < players.size(); i++) {
			if (VanishHooks.hiddenFromLists(players.get(i))) hidden++;
		}
		if (hidden > 0) {
			cir.setReturnValue(Math.max(0, cir.getReturnValue() - hidden));
		}
	}
}
