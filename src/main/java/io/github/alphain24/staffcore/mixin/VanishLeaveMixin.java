package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.vanish.VanishModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forgets a player's vanish state, but only once the leave line has already gone out.
 * <p>
 * Timing is the entire point of this class. Vanilla disconnects in this order:
 * <ol>
 *   <li>{@code removePlayerFromWorld} broadcasts {@code multiplayer.player.left};</li>
 *   <li>{@code PlayerList#remove} tears the player down.</li>
 * </ol>
 * Fabric's {@code ServerPlayConnectionEvents.DISCONNECT} fires before <em>both</em> — its
 * hook sits at the head of {@code Connection#handleDisconnection}. Clearing vanish state
 * from that event therefore emptied every map {@code shouldSuppressBroadcast} consults
 * before the message it was meant to suppress had even been built, which is why a vanished
 * staff member still announced their departure.
 * <p>
 * Injecting at the head of {@code remove} puts the cleanup on the far side of the
 * broadcast, so suppression sees the state it needs and nothing is left behind afterwards.
 */
@Mixin(PlayerList.class)
public class VanishLeaveMixin {

	@Inject(method = "remove", at = @At("HEAD"))
	private void staffcore$forgetVanishAfterLeaveLine(ServerPlayer player, CallbackInfo ci) {
		StaffCore.modules().get("vanish", VanishModule.class)
				.ifPresent(vanish -> vanish.onPlayerLeft(player.getUUID()));
	}
}
