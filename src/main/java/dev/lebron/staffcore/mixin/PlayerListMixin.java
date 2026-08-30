package dev.lebron.staffcore.mixin;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.modules.control.ControlModule;
import dev.lebron.staffcore.modules.punish.Punishment;
import dev.lebron.staffcore.modules.punish.PunishmentModule;
import dev.lebron.staffcore.permission.Nodes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * The login gate: bans and maintenance mode.
 * <p>
 * Fabric has no allow-login event, and {@code canPlayerLogin} is the one place vanilla
 * asks "may this profile in?" before anything is allocated for them. Returning a
 * component from here is exactly how vanilla's own ban list refuses a connection, so a
 * StaffCore ban behaves identically to a vanilla one from the client's point of view.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

	@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
	private void staffcore$gateLogin(SocketAddress address, NameAndId profile,
			CallbackInfoReturnable<Component> cir) {

		PunishmentModule punish = StaffCore.modules()
				.get("punishment", PunishmentModule.class).orElse(null);
		if (punish != null) {
			Punishment ban = punish.activeBan(profile.id());
			if (ban != null) {
				cir.setReturnValue(punish.disconnectScreen(ban));
				return;
			}
		}

		ControlModule control = StaffCore.modules()
				.get("control", ControlModule.class).orElse(null);
		if (control != null && control.isMaintenance() && !StaffCore.isOperator(profile)) {
			// Vanilla's op check is the fallback here rather than Permissions.check: there
			// is no online ServerPlayer yet to hand to a permissions provider.
			cir.setReturnValue(ControlModule.maintenanceScreen());
		}
	}
}
