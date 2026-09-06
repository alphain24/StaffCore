package io.github.alphain24.staffcore.mixin.vanish;

import io.github.alphain24.staffcore.modules.vanish.VanishHooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps vanished players out of the server-list ping entirely — count and names.
 * <p>
 * The multiplayer screen shows "1/20" and a handful of names on hover, before anyone has
 * connected and with no way for staff to notice they have been spotted. A vanished admin
 * appearing in either tells the whole internet that somebody is on.
 * <p>
 * The count was the half that leaked. {@code PlayerList#getPlayerCount} is filtered
 * elsewhere, and it looked like that covered this — but {@code buildPlayerStatus} does not
 * call it. It takes {@code getPlayers()} and reads {@code size()} off the list directly, so
 * the filtered count was never consulted and the ping reported the real number.
 * <p>
 * Redirecting the list itself fixes both halves at once, because the sample is drawn by
 * indexing into the very same list. An earlier version filtered only the sample and rebuilt
 * the record with {@code players.online()} carried over untouched — which is to say it
 * carefully removed the name and left the number that proved it.
 * <p>
 * There is no viewer to check here: a ping is anonymous, so everyone hidden is hidden from
 * it. Called off the server thread, which is why {@code VanishState} is a concurrent map.
 */
@Mixin(MinecraftServer.class)
public class VanishStatusMixin {

	@Redirect(method = "buildPlayerStatus",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
	private List<ServerPlayer> staffcore$hideFromPing(PlayerList list) {
		List<ServerPlayer> everyone = list.getPlayers();
		if (VanishHooks.noneHidden()) return everyone;

		List<ServerPlayer> visible = new ArrayList<>(everyone.size());
		for (ServerPlayer player : everyone) {
			if (!VanishHooks.hiddenFromLists(player)) visible.add(player);
		}
		return visible;
	}
}
