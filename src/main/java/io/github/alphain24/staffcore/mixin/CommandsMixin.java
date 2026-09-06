package io.github.alphain24.staffcore.mixin;

import com.mojang.brigadier.ParseResults;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.modules.control.ControlModule;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command spy.
 * <p>
 * Lives in the optional mixin config: losing this costs one feature, and
 * {@code performCommand} has picked up and dropped parameters across recent releases, so
 * it is not worth taking the whole mod down for.
 */
@Mixin(Commands.class)
public class CommandsMixin {

	@Inject(method = "performCommand", at = @At("HEAD"))
	private void staffcore$spy(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
		ControlModule control = StaffCore.modules()
				.get("control", ControlModule.class).orElse(null);
		if (control == null) return;

		CommandSourceStack source = parseResults.getContext().getSource();
		ServerPlayer sender = source.getPlayer();
		if (sender == null) return; // console commands are already in the log

		MinecraftServer server = Mc.server(sender);
		if (server == null) return;

		control.reportCommand(server, Mc.name(sender), "/" + command);
	}
}
