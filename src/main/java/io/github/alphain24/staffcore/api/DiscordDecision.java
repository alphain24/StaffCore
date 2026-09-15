package io.github.alphain24.staffcore.api;

/**
 * Whether a Discord user may do something, and if not, the sentence to show them.
 * <p>
 * Advisory for the companion — a button can be hidden on a no — and never the last word: the
 * action itself checks again, on the server thread, against permissions read at that moment.
 */
public record DiscordDecision(boolean allowed, String refusal) {

	public static DiscordDecision yes() {
		return new DiscordDecision(true, null);
	}

	public static DiscordDecision no(String why) {
		return new DiscordDecision(false, why);
	}
}
