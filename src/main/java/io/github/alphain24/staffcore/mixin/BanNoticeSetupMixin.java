package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.modules.appeal.BanNotice;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Queues the appeal window for a connection whose ban was deferred at login.
 * <p>
 * At the end of vanilla's optional setup tasks, because of what comes either side: the
 * registries have been sent, so the client is ready to draw a window; the spawn has not been
 * prepared, so nothing of the banned player's world is loaded for them. Fabric's own setup
 * event fires earlier than the registries, which is why this is not a listener on it.
 * <p>
 * If this fails to apply, the deferred ban is refused at the end of setup with the ordinary
 * ban screen.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class BanNoticeSetupMixin {

	@Inject(method = "addOptionalTasks", at = @At("TAIL"))
	private void staffcore$queueBanNotice(CallbackInfo ci) {
		ServerConfigurationPacketListenerImpl self = (ServerConfigurationPacketListenerImpl) (Object) this;
		BanNotice.queue(self, task ->
				((FabricServerConfigurationPacketListenerImpl) self).addTask(task));
	}
}
