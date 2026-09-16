package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.api.DiscordLadder;

import java.util.List;

/**
 * The punishment panel: a message in a private channel with a button that punishes by offence ladder,
 * as the punish screen in game does.
 * <p>
 * Every step is private to whoever pressed it: the player's record, the offences with what each would do
 * now, and a confirmation naming exactly what will be issued. Nothing is issued before that confirmation,
 * and if the player's record changes in between, nothing is issued at all.
 */
public final class PunishPanel {
	private PunishPanel() {}

	public static final String KEY = "panel:punish";

	/** Every id the panel's buttons, form and menu use starts with this. */
	public static final String PREFIX = "sc:pp:";
	public static final String OPEN = PREFIX + "open:0";
	public static final String PLAYER_FORM = PREFIX + "player:0";
	public static final String PICK = PREFIX + "pick:";
	public static final String CONFIRM = PREFIX + "ok:";
	public static final String CANCEL = PREFIX + "no:";

	private static final int COLOUR = 0xE0563C;

	public static Outbound.Message message() {
		Embed embed = Embed.builder("Punish a player")
				.color(COLOUR)
				.description("""
						Punishes by the server's offence ladders, the way the punish screen in game does: pick \
						what they did, and their record decides what happens.

						Press **Punish**, name the player, pick the offence, and confirm. Everything you see is \
						private to you. You need `discord.punishpanel`, and the permission for whatever the \
						ladder picks, in game and in your Discord role.""")
				.footer("Every punishment is recorded against your Minecraft account.")
				.build();
		return Outbound.Message.of(embed).withRows(List.of(List.of(
				new Outbound.Button(OPEN, "Punish", Outbound.Button.Style.DANGER, false))));
	}

	/** The player's record and the ladder, above the menu to pick an offence from. */
	public static Embed ladder(DiscordLadder ladder) {
		Embed.Builder embed = Embed.builder("Punish · " + ladder.playerName())
				.color(COLOUR)
				.description(ladder.punishments() == 0 ? "No punishments on record."
						: ladder.punishments() + " punishment(s) on record.");
		if (ladder.activeBan() != null) embed.field("Banned now", Text.safe(ladder.activeBan(), 300));
		if (ladder.activeMute() != null) embed.field("Muted now", Text.safe(ladder.activeMute(), 300));
		StringBuilder rungs = new StringBuilder();
		for (DiscordLadder.Rung rung : ladder.rungs()) {
			String line = "**" + Text.safe(rung.label(), 60) + "** — " + Text.safe(rung.applies(), 40)
					+ (rung.priors() == 0 ? " (first time)" : " (" + rung.priors() + " before)")
					+ (rung.allowed() ? "" : " — not yours to give");
			if (rungs.length() + line.length() + 1 > 1000) break;
			if (rungs.length() > 0) rungs.append('\n');
			rungs.append(line);
		}
		if (rungs.length() > 0) embed.field("Offences", rungs.toString());
		return embed.build();
	}

	/** What will be issued, above Confirm and Cancel. */
	public static Embed confirmation(String playerName, DiscordLadder.Rung rung) {
		return Embed.builder("Confirm · " + rung.applies() + " for " + playerName)
				.color(COLOUR)
				.field("Offence", Text.safe(rung.label(), 100))
				.field("Their record", rung.priors() == 0 ? "First time for this" : rung.priors() + " before for this")
				.field("Issues", Text.safe(rung.applies(), 60))
				.field("Next time", rung.next() == null ? "Top of the ladder" : Text.safe(rung.next(), 60))
				.footer("Nothing happens until you confirm. This expires in five minutes.")
				.build();
	}

	/** The option value for an offence: its id and the record it was shown with. */
	public static String optionValue(DiscordLadder.Rung rung) {
		return rung.offenceId() + "|" + rung.priors();
	}
}
