package io.github.alphain24.staffcore.discord.gateway;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

/**
 * The shape of {@code /staff} in Discord: the same subcommands staff type in game, with the same names.
 * <p>
 * Shown to everybody in the server, because Discord's own command permissions are set per role by server
 * admins and not something the bot can keep in step with StaffCore's. That costs nothing: somebody who may
 * not run one is told so, privately, by StaffCore.
 */
final class StaffCommands {
	private StaffCommands() {}

	static SlashCommandData definition() {
		return Commands.slash("staff", "StaffCore staff commands")
				.addSubcommands(
						sub("history", "A player's punishment history").addOptions(player()),
						sub("staff-history", "What a staff member has done")
								.addOptions(new OptionData(OptionType.STRING, "staff", "The staff member", true, true),
										new OptionData(OptionType.INTEGER, "days", "How many days back, 1-90 (7 if left out)", false)
												.setRequiredRange(1, 90)),
						sub("notes", "A player's notes").addOptions(player()),
						sub("evidence", "The evidence filed on a case").addOptions(caseId()),
						sub("case", "A case and the latest of its history").addOptions(caseId()),
						sub("profile", "A player's standing").addOptions(player()),
						sub("analytics", "Server totals and staff numbers")
								.addOptions(new OptionData(OptionType.STRING, "staff", "One staff member (the busiest if left out)",
										false, true)),
						sub("ban", "Ban a player").addOptions(player(), reason(), duration()),
						sub("unban", "Lift a player's ban").addOptions(player(), optionalReason()),
						sub("mute", "Mute a player").addOptions(player(), reason(), duration()),
						sub("unmute", "Lift a player's mute").addOptions(player(), optionalReason()),
						sub("warn", "Warn a player").addOptions(player(), reason()),
						sub("freeze", "Freeze a player who is online").addOptions(player()),
						sub("unfreeze", "Release a frozen player").addOptions(player()),
						sub("note", "Add a note to a player")
								.addOptions(player(), new OptionData(OptionType.STRING, "text", "The note", true)
										.setMaxLength(256)));
	}

	private static SubcommandData sub(String name, String description) {
		return new SubcommandData(name, description);
	}

	private static OptionData player() {
		return new OptionData(OptionType.STRING, "player", "The player's Minecraft name", true, true);
	}

	private static OptionData reason() {
		return new OptionData(OptionType.STRING, "reason", "Why", true).setMaxLength(256);
	}

	private static OptionData optionalReason() {
		return new OptionData(OptionType.STRING, "reason", "Why (optional)", false).setMaxLength(256);
	}

	private static OptionData duration() {
		return new OptionData(OptionType.STRING, "duration", "How long: 30m, 12h, 7d (permanent if left out)", false)
				.setMaxLength(16);
	}

	private static OptionData caseId() {
		return new OptionData(OptionType.STRING, "case", "The case id, like ABCD2345", true).setMaxLength(16);
	}
}
