package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.modules.freeze.FreezeModule;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells freeze when the server itself is ending a connection, and why.
 * <p>
 * A frozen player who quits is reported; one the server kicks, bans or shuts down on is not. By the
 * time Fabric's disconnect event fires, both look the same. This method is only called when the server
 * ends a connection (every kick, ban and shutdown goes through it), so being called at all is the
 * difference. The reason is passed along too, because a timeout also comes through here and is still
 * reported.
 * <p>
 * If this stops applying, every disconnect of a frozen player reads as leaving, kicks included. That is
 * the safe way to be wrong: staff are told too often rather than not at all.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class FreezeDisconnectMixin {

	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void staffcore$noteServerDisconnect(DisconnectionDetails details, CallbackInfo ci) {
		var owner = ((ServerCommonPacketListenerImpl) (Object) this).getOwner();
		if (owner == null) return;
		StaffCore.modules().get("freeze", FreezeModule.class)
				.ifPresent(freeze -> freeze.serverDisconnecting(owner.id(), details.reason()));
	}
}
