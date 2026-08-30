package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.modules.vanish.VanishModule;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences the join and leave announcements of a vanished staff member.
 * <p>
 * The order here is the whole problem. Vanilla broadcasts "X joined the game" from inside
 * {@code placeNewPlayer}, long before any Fabric join event fires, so StaffCore used to be
 * told about the arrival strictly after the server had already announced it — which is why
 * the old behaviour was to give up and ask the staff member to re-vanish by hand.
 * <p>
 * Marking the player as vanished at the <em>head</em> of {@code placeNewPlayer}, from
 * persisted state, means the broadcast that follows is already suppressible. The flag is a
 * plain field on the module rather than a thread-local because player placement happens on
 * the server thread, one player at a time.
 */
@Mixin(PlayerList.class)
public class VanishJoinMixin {

	@Inject(method = "placeNewPlayer", at = @At("HEAD"))
	private void staffcore$restoreVanishEarly(net.minecraft.network.Connection connection,
			ServerPlayer player, net.minecraft.server.network.CommonListenerCookie cookie,
			CallbackInfo ci) {

		VanishModule vanish = StaffCore.modules().get("vanish", VanishModule.class).orElse(null);
		if (vanish != null) {
			vanish.restoreBeforeJoin(player);
		}
	}

	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void staffcore$suppressVanishedBroadcast(Component message, boolean overlay, CallbackInfo ci) {
		VanishModule vanish = StaffCore.modules().get("vanish", VanishModule.class).orElse(null);
		if (vanish != null && vanish.shouldSuppressBroadcast(message)) {
			ci.cancel();
		}
	}

	/**
	 * Keeps a vanished player out of everyone else's tab list at the moment they join.
	 * <p>
	 * This is the one {@code broadcastAll} in {@code placeNewPlayer}, and it hands the new
	 * player's list entry to every client on the server. Sending it and then chasing it with
	 * a removal packet — which is what StaffCore used to do, a tick later — is a race it can
	 * only lose sometimes, and "sometimes" is the worst possible property for a feature whose
	 * entire job is that nobody notices you. Not sending it is unconditional.
	 * <p>
	 * Vanish state is already known by this point: {@code restoreBeforeJoin} runs at the head
	 * of the same method, before any of this.
	 */
	@Redirect(method = "placeNewPlayer",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
	private void staffcore$hideJoinFromTabList(PlayerList list, Packet<?> packet,
			net.minecraft.network.Connection connection, ServerPlayer player,
			net.minecraft.server.network.CommonListenerCookie cookie) {

		VanishModule vanish = StaffCore.modules().get("vanish", VanishModule.class).orElse(null);
		if (vanish == null || !vanish.isVanished(player)) {
			list.broadcastAll(packet);
			return;
		}

		// The player still needs their own entry — a client with no entry for itself renders
		// its own name wrong in the tab list — as does anyone cleared to see through vanish.
		for (ServerPlayer viewer : list.getPlayers()) {
			if (viewer == player || vanish.canSeeVanished(viewer)) {
				viewer.connection.send(packet);
			}
		}
	}
}
