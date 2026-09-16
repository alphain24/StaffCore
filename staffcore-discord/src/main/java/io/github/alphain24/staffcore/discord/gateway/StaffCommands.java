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
						sub("evidence", "The evidence filed on a case, or one piece of it with its files")
								.addOptions(caseId(), new OptionData(OptionType.INTEGER, "item",
										"One piece of evidence, by its number, with any files kept", false)
										.setMinValue(1)),
						sub("punish", "Punish a player by an offence ladder (the punishment panel's permission)")
								.addOptions(player(), new OptionData(OptionType.STRING, "offence", "What they did", true, true)
										.setMaxLength(64)),
						sub("replay-map", "A map of where a player went and what they broke (staff.replay)")
								.addOptions(player(),
										new OptionData(OptionType.INTEGER, "minutes", "How long the window is (30 if left out)", false)
												.setRequiredRange(1, 360),
										new OptionData(OptionType.INTEGER, "started", "How many minutes ago it starts (the last so-many minutes if left out)", false)
												.setRequiredRange(1, 43_200),
										new OptionData(OptionType.STRING, "case", "Also file the window as replay evidence on this case", false)
												.setMaxLength(16).setAutoComplete(true)),
						sub("evidence-add", "File a note or a file as evidence on a case")
								.addOptions(caseId(),
										new OptionData(OptionType.ATTACHMENT, "file", "A screenshot, video or log", false),
										new OptionData(OptionType.STRING, "note", "What it shows", false).setMaxLength(500)),
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
		return new OptionData(OptionType.STRING, "case", "The case id, like ABCD2345", true).setMaxLength(16)
				.setAutoComplete(true);
	}
}
