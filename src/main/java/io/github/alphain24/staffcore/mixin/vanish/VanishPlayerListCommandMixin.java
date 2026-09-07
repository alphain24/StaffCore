package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps vanished players out of {@code /list}.
 * <p>
 * {@code getPlayerCount} was already filtered, so the count and the names disagreed: the
 * header said four players and then printed five names, one of which belonged to somebody
 * who had just gone to some trouble to be invisible. Anybody who ran the command twice —
 * once before an admin vanished and once after — could read the difference straight off.
 * <p>
 * Redirected at this one call site rather than filtering {@code getPlayers} globally. The
 * server walks that list every tick to tick, save and broadcast; a vanished player who
 * vanished out of it would stop being ticked, which is a considerably worse bug than the one
 * being fixed. Here the caller wants a human-facing roster, which is exactly the case where
 * hiding is correct.
 * <p>
 * Staff who can see through vanish get the real list, so nobody is left wondering where a
 * colleague went.
 */
@Mixin(ListPlayersCommand.class)
public class VanishPlayerListCommandMixin {

	@Redirect(method = "format",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
	private static List<ServerPlayer> staffcore$hideFromList(PlayerList list,
			CommandSourceStack source, java.util.function.Function<ServerPlayer, ?> formatter) {

		List<ServerPlayer> everyone = list.getPlayers();
		if (VanishHooks.noneHidden()) return everyone;

		// The console and command blocks have no player to check, and get the truth.
		ServerPlayer asker = source.getPlayer();
		if (asker == null || VanishHooks.canSeeVanished(asker)) return everyone;

		List<ServerPlayer> visible = new ArrayList<>(everyone.size());
		for (ServerPlayer player : everyone) {
			if (!VanishHooks.hiddenFromLists(player) || player == asker) visible.add(player);
		}
		return visible;
	}
}
