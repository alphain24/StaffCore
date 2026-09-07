package io.github.alphain24.staffcore.modules.alerts;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.modules.discord.DiscordModule;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The alert bus. Modules call {@code on*()}; Alerts decides who hears about it.
 * <p>
 * Subscription is opt-out: a staff member with {@link Nodes#ALERTS} receives everything
 * until they toggle it off, because the failure mode of a quiet alert system is that
 * nobody notices the thing it was built to catch.
 */
public class AlertsModule implements Module {

	@Override
	public String id() {
		return "alerts";
	}

	@Override
	public String displayName() {
		return "Alerts";
	}

	/** Holds the staff who have explicitly opted *out*. */
	private final Set<UUID> muted = new HashSet<>();

	public boolean toggle(ServerPlayer staff) {
		if (muted.remove(staff.getUUID())) {
			Sfx.toggleOn(staff);
			return true;   // now subscribed
		}
		muted.add(staff.getUUID());
		Sfx.toggleOff(staff);
		return false;      // now silenced
	}

	public boolean isSubscribed(ServerPlayer staff) {
		return !muted.contains(staff.getUUID()) && Permissions.check(staff, Nodes.ALERTS);
	}

	// ------------------------------------------------------------------ producers

	public void onPunishment(MinecraftServer server, String staff, String target,
			PunishmentType type, String reason) {
		discordOnly("punish", "%s %s %s — %s".formatted(staff, type.pastTense(), target, reason));
	}

	public void onReport(MinecraftServer server, String reporter, String target, String reason) {
		broadcast(server, "Report", "%s reported %s — %s".formatted(reporter, target, reason), Theme.WARN);
	}

	public void onSecurityFlag(MinecraftServer server, String player, String detail) {
		broadcast(server, "Security", "%s — %s".formatted(player, detail), Theme.BAD);
	}

	public void onSuspiciousMining(MinecraftServer server, String player, String detail) {
		broadcast(server, "X-ray", "%s — %s".formatted(player, detail), Theme.BAD);
	}

	public void onPerformance(MinecraftServer server, String detail) {
		broadcast(server, "Performance", detail, Theme.WARN);
	}

	public void onStaffAction(MinecraftServer server, String detail) {
		broadcast(server, "Staff", detail, Theme.MUTED);
	}

	// ------------------------------------------------------------------- delivery

	/**
	 * Pings subscribed staff in game and mirrors to Discord.
	 * Punishments skip the in-game half — {@code PunishmentModule} already broadcasts
	 * those itself, and hearing the same event twice trains people to ignore both.
	 */
	private void broadcast(MinecraftServer server, String category, String message, int color) {
		Component line = Theme.prefix()
				.append(Icon.text(category, color))
				.append(Icon.text(" · ", Theme.MUTED))
				.append(Icon.text(message, Theme.TEXT));

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (isSubscribed(p)) {
				p.sendSystemMessage(line);
				Sfx.alertPing(p);
			}
		}
		StaffCore.LOGGER.info("[Alert:{}] {}", category, message);
		toDiscord(category, message);
	}

	private void discordOnly(String category, String message) {
		StaffCore.LOGGER.info("[Alert:{}] {}", category, message);
		toDiscord(category, message);
	}

	private void toDiscord(String category, String message) {
		StaffCore.modules().get("discord", DiscordModule.class)
				.ifPresent(d -> d.postAlert(category, message));
	}
}
