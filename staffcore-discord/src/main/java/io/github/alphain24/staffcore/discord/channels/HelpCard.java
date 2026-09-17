package io.github.alphain24.staffcore.discord.channels;

import io.github.alphain24.staffcore.discord.channels.Outbound.Button;
import io.github.alphain24.staffcore.discord.channels.Outbound.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A help request as staff see it in the help requests channel: who asked, as whom, what StaffCore knows
 * about that player, and the buttons to join the conversation or close it.
 */
public final class HelpCard {
	private HelpCard() {}

	private static final Pattern SNOWFLAKE = Pattern.compile("\\d{15,22}");
	private static final Pattern CASE_ID = Pattern.compile("[0-9A-Z]{1,16}");

	public static Message message(HelpDesk.Request r, String headUrl) {
		boolean closed = !r.open();
		Embed.Builder embed = Embed.builder("Help request #" + r.id() + " · " + Text.safe(r.minecraftName(), 32))
				.color(closed ? Router.QUIET : r.frozen() ? Router.SEVERE : Router.NOTICE)
				.thumbnail(r.playerId() == null ? null : headUrl)
				.description(Text.safe(r.text(), 1500))
				.inline("Discord", account(r))
				.inline("Player", player(r))
				.inline("Case", r.caseId() == null || !CASE_ID.matcher(r.caseId()).matches() ? "None" : "`" + r.caseId() + "`")
				.inline("Staff", r.staff().isEmpty() ? "Nobody yet"
						: String.join(", ", r.staff().stream().map(s -> Text.safe(s, 32)).toList()))
				.inline("Status", closed ? "Closed by " + Text.safe(r.closedBy(), 64) + " " + Text.relative(r.closedAt())
						: "Open")
				.inline("Opened", Text.when(r.openedAt()))
				.footer("Help request #" + r.id() + " · Join to be added to the player's private thread")
				.timestamp(r.openedAt());
		return new Message(null, embed.build(), buttons(r, closed));
	}

	/** Whose Discord account this is, and whether it is the player it says. */
	static String account(HelpDesk.Request r) {
		String mention = r.discordId() != null && SNOWFLAKE.matcher(r.discordId()).matches()
				? "<@" + r.discordId() + ">" : Text.safe(r.discordName(), 64);
		if (r.linkedTo() == null) return mention + " · not linked, so the name is only what they typed";
		if (r.linkedTo().equalsIgnoreCase(r.minecraftName())) return mention + " · linked to this player";
		return mention + " · linked to **" + Text.safe(r.linkedTo(), 32) + "**, not this player";
	}

	/** What the server knows about the named player. */
	static String player(HelpDesk.Request r) {
		if (r.playerId() == null) return "Nobody called " + Text.safe(r.minecraftName(), 32) + " has joined";
		List<String> state = new ArrayList<>();
		state.add(r.online() ? "online" : "offline");
		if (r.frozen()) state.add("**frozen**");
		if (r.banned()) state.add("banned");
		return String.join(" · ", state);
	}

	static List<List<Button>> buttons(HelpDesk.Request r, boolean closed) {
		List<List<Button>> rows = new ArrayList<>();
		rows.add(List.of(
				new Button("sc:helpjoin:" + r.id(), "Join", Button.Style.PRIMARY, closed),
				new Button("sc:helpclose:" + r.id(), "Close", Button.Style.SECONDARY, closed)));
		if (r.playerId() != null) {
			rows.add(List.of(
					new Button("sc:profile:" + r.playerId(), "Profile", Button.Style.SECONDARY, false),
					new Button("sc:history:" + r.playerId(), "History", Button.Style.SECONDARY, false),
					new Button("sc:freeze:" + r.playerId(), "Freeze", Button.Style.DANGER, closed),
					new Button("sc:unfreeze:" + r.playerId(), "Unfreeze", Button.Style.SUCCESS, closed)));
		}
		return rows;
	}
}
