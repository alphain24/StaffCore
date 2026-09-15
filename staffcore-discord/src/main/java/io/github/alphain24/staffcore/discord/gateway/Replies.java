package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordAnswer;
import io.github.alphain24.staffcore.api.DiscordProfile;
import io.github.alphain24.staffcore.api.DiscordPunishment;
import io.github.alphain24.staffcore.api.DiscordStanding;
import io.github.alphain24.staffcore.discord.channels.Text;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * What the bot says back, as plain text, apart from Discord so it can be read in a test.
 */
public final class Replies {
	private Replies() {}

	/** The answer to {@code /whoami}. */
	public static String standing(DiscordStanding standing) {
		if (!standing.linked()) return standing.limitation();

		StringBuilder out = new StringBuilder("Linked to **").append(standing.minecraftName()).append("**.");
		if (standing.nodes().isEmpty()) {
			out.append("\nYou can use nothing from Discord");
			out.append(standing.limitation() == null ? "." : ": " + standing.limitation());
		} else {
			out.append("\nYou can use: ").append(String.join(", ", standing.nodes()));
		}
		return out.toString();
	}

	/** A player's standing, for the Profile button. */
	public static String profile(DiscordAnswer<DiscordProfile> answer) {
		if (!answer.answered()) return answer.refusal();
		DiscordProfile p = answer.value();
		StringBuilder out = new StringBuilder("**").append(Text.safe(p.name(), 64)).append("**")
				.append(p.online() ? " — online" : " — offline");
		out.append("\nPunishments: ").append(p.punishments())
				.append(" · Warning points: ").append(p.warningPoints())
				.append(" · Notes: ").append(p.notes())
				.append(" · Open appeals: ").append(p.openAppeals());
		out.append("\nBan: ").append(p.activeBan() == null ? "none" : inForce(p.activeBan()));
		out.append("\nMute: ").append(p.activeMute() == null ? "none" : inForce(p.activeMute()));
		out.append("\nOpen cases: ").append(p.openCases().isEmpty() ? "none"
				: String.join(", ", p.openCases().stream().map(id -> "`" + Text.clip(id, 16).replace('`', '\'') + "`")
						.toList()));
		return out.toString();
	}

	/** Their punishments, newest first, for the History button. */
	public static String history(DiscordAnswer<List<DiscordPunishment>> answer) {
		if (!answer.answered()) return answer.refusal();
		if (answer.value().isEmpty()) return "No punishments on record.";
		StringBuilder out = new StringBuilder();
		for (DiscordPunishment p : answer.value()) {
			String line = "#" + p.id() + " **" + Text.punishment(p.type()) + "** " + Text.relative(p.issuedAt())
					+ " by " + Text.safe(p.staffName(), 32) + " — " + Text.safe(p.reason(), 120)
					+ (p.reversedBy() != null ? " *(reversed by " + Text.safe(p.reversedBy(), 32) + ")*"
							: p.active() ? " *(in force)*" : "");
			if (out.length() + line.length() + 1 > 1900) {
				out.append("\n…");
				break;
			}
			if (out.length() > 0) out.append('\n');
			out.append(line);
		}
		return out.toString();
	}

	private static String inForce(DiscordPunishment p) {
		return Text.punishment(p.type()) + " — " + Text.safe(p.reason(), 200)
				+ (p.expiresAt() == null ? ", permanent" : ", ends " + Text.relative(p.expiresAt()));
	}

	/**
	 * What to say when StaffCore did not answer.
	 * <p>
	 * Never the exception's message: it came from somewhere in the server and could say anything.
	 */
	public static String failure(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause() : failure;
		if (cause instanceof TimeoutException) {
			return "The server did not answer in time. It may be busy; if what you asked for changes "
					+ "something, check before asking again.";
		}
		return "Something went wrong on the server, so nothing was done. The server log has the details.";
	}
}
