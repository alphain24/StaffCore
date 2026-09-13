package io.github.alphain24.staffcore.mixin;

import io.github.alphain24.staffcore.modules.appeal.BanNotice;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hears the appeal window's Leave button.
 * <p>
 * Vanilla passes a custom click on to the server without saying which connection it came
 * from, so it is read here, where the connection is known. After the thread hop, so it runs
 * once and on the server thread.
 * <p>
 * If this fails to apply, Leave does nothing and the window closes itself when its time is up.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class BanNoticeClickMixin {

	@Inject(method = "handleCustomClickAction",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
					shift = At.Shift.AFTER))
	private void staffcore$hearLeave(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		if ((Object) this instanceof ServerConfigurationPacketListenerImpl setup) {
			BanNotice.onClick(setup, packet.id());
		}
	}
}
