package dev.lebron.staffcore.modules.chat;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.gui.Icon;
import dev.lebron.staffcore.gui.Sfx;
import dev.lebron.staffcore.gui.Theme;
import dev.lebron.staffcore.module.Module;
import dev.lebron.staffcore.permission.Nodes;
import dev.lebron.staffcore.permission.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An isolated channel for staff.
 * <p>
 * Two ways in: {@code /sc &lt;message&gt;} for a one-off, or toggle the channel on and
 * every normal chat line you send is rerouted. The reroute happens in
 * {@link dev.lebron.staffcore.chat.ChatRouter}, which owns the single chat event handler.
 */
public class StaffChatModule implements Module {

	@Override
	public String id() {
		return "staff_chat";
	}

	@Override
	public String displayName() {
		return "Staff Chat";
	}

	private final Set<UUID> toggled = new HashSet<>();

	public boolean toggle(ServerPlayer p) {
		if (toggled.remove(p.getUUID())) {
			Sfx.toggleOff(p);
			p.sendSystemMessage(Theme.info("Staff chat off — you are talking to everyone again."));
			return false;
		}
		toggled.add(p.getUUID());
		Sfx.toggleOn(p);
		p.sendSystemMessage(Theme.good("Staff chat on — everything you type goes to staff only."));
		return true;
	}

	public boolean isToggled(ServerPlayer p) {
		return toggled.contains(p.getUUID());
	}

	public void forget(UUID player) {
		toggled.remove(player);
	}

	/**
	 * Sends a line to everyone holding {@link Nodes#CHAT}, plus the console log.
	 *
	 * @return how many staff received it, including the sender
	 */
	public int send(MinecraftServer server, String senderName, String message) {
		Component line = Theme.prefix()
				.append(Icon.text("Staff ", Theme.ACCENT).withStyle(s -> s.withBold(true)))
				.append(Icon.text(senderName, Theme.TEXT))
				.append(Icon.text(": ", Theme.MUTED))
				.append(Icon.text(message, Theme.TEXT));

		int delivered = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Permissions.check(p, Nodes.CHAT)) {
				p.sendSystemMessage(line);
				Sfx.staffChatPing(p);
				delivered++;
			}
		}
		StaffCore.LOGGER.info("[StaffChat] {}: {} (to {} staff)", senderName, message, delivered);
		return delivered;
	}

	/**
	 * Sends, then tells the sender if they were the only one listening.
	 * <p>
	 * Without this, a staff channel with nobody else online is indistinguishable from a
	 * broken one — you type, you see your own line, and you cannot tell whether it went
	 * anywhere. Saying so outright is the difference between "no audience" and "no feature".
	 */
	public void sendFrom(MinecraftServer server, ServerPlayer sender, String message) {
		int delivered = send(server, sender.nameAndId().name(), message);
		if (delivered <= 1) {
			sender.sendSystemMessage(Theme.warn(
					"Nobody else with staff.chat is online — that went to you only."));
		}
	}
}
