package io.github.alphain24.staffcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.alphain24.staffcore.modules.appeal.BanNotice;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.SocketAddress;

/**
 * Marks vanilla's login-stage "may they in?" check as the login one.
 * <p>
 * Vanilla asks twice — here, and again at the end of setup — and the login gate cannot tell
 * the two calls apart on its own. Only this one may let a banned player continue far enough
 * to be shown the appeal window. A wrap rather than a pair of injections, because the mark has
 * to be cleared even when the check throws; a mark left behind would reach the end-of-setup
 * check, which is the one that keeps banned players out.
 * <p>
 * If this fails to apply, bans are refused at login exactly as they were before.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class BanNoticeLoginMixin {

	@WrapOperation(method = "verifyLoginAndFinishConnectionSetup",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(Ljava/net/SocketAddress;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/network/chat/Component;"))
	private Component staffcore$markLoginStage(PlayerList players, SocketAddress address,
			NameAndId profile, Operation<Component> original) {

		return BanNotice.atLogin(() -> original.call(players, address, profile));
	}
}
