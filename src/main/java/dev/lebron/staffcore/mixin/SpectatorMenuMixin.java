package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.gui.Gui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets StaffCore menus be clicked while the viewer is a spectator.
 * <p>
 * Vanilla drops container clicks outright for spectators: {@code handleContainerClick}
 * asks {@code isSpectator}, re-sends the window and returns before it ever reaches
 * {@code clicked}. That is correct for a chest — a spectator has no business moving items —
 * but it also silently killed every button in this mod the moment noclip put a staff
 * member into spectator, which is exactly when they are most likely to want the panel.
 * <p>
 * The redirect is deliberately narrow: it answers "no" only when the open menu is one of
 * ours. A spectator still cannot touch a real chest, because a StaffCore menu is a control
 * surface rather than storage — {@link Gui} refuses item movement on its own.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SpectatorMenuMixin {

	@Redirect(
			method = "handleContainerClick",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean staffcore$allowStaffMenus(ServerPlayer player) {
		if (player.containerMenu instanceof Gui) {
			return false;
		}
		return player.isSpectator();
	}
}
