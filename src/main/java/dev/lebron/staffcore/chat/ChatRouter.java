package dev.lebron.staffcore.chat;

import dev.lebron.staffcore.compat.Mc;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Mods;
import dev.lebron.staffcore.modules.punish.Punishment;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The single chat gate.
 * <p>
 * Three modules want a say in whether a message goes out — mutes, the global chat lock,
 * and the staff channel — and registering three independent handlers means the order they
 * run in depends on module registration order. One handler with an explicit order is
 * boring, and boring is correct: a muted player must not be able to reach staff chat, and
 * a chat lock must not silence the staff coordinating through it.
 */
public final class ChatRouter {
	private ChatRouter() {}

	public static void register() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
			String text = message.signedContent();
			MinecraftServer server = Mc.server(sender);
			if (server == null) return true;

			// 1. A mute beats everything, including the staff channel.
			Punishment mute = Mods.punish().activeMute(sender.getUUID());
			if (mute != null) {
				sender.sendSystemMessage(Theme.bad("You are muted — " + mute.reason()));
				sender.sendSystemMessage(Theme.warn("Expires: " + mute.remaining()));
				Sfx.muted(sender);
				return false;
			}

			// 2. Staff channel next, so a chat lock never stops staff talking to each other.
			if (Mods.staffChat().isToggled(sender) && Permissions.check(sender, Nodes.CHAT)) {
				Mods.staffChat().sendFrom(server, sender, text);
				return false;
			}

			// 3. Global lock last: it is the broadest and least specific rule.
			if (Mods.control().isChatMuted() && !Permissions.check(sender, Nodes.CHAT_CONTROL)) {
				sender.sendSystemMessage(Theme.warn("Chat is locked by staff right now."));
				Sfx.deny(sender);
				return false;
			}

			return true;
		});
	}

	/** Wipes per-player chat state when someone disconnects. */
	public static void onPlayerLeft(ServerPlayer player) {
		Mods.staffChat().forget(player.getUUID());
	}
}
