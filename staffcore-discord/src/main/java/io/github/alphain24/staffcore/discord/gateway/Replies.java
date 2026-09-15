package io.github.alphain24.staffcore.discord.gateway;

import io.github.alphain24.staffcore.api.DiscordStanding;

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
