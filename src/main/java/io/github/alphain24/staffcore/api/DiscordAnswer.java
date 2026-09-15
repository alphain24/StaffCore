package io.github.alphain24.staffcore.api;

/**
 * The answer to a question asked from Discord, or why there is none.
 *
 * @param value   the answer, or null when refused
 * @param refusal why it was refused, or null
 */
public record DiscordAnswer<T>(T value, String refusal) {

	public static <T> DiscordAnswer<T> of(T value) {
		return new DiscordAnswer<>(value, null);
	}

	public static <T> DiscordAnswer<T> no(String why) {
		return new DiscordAnswer<>(null, why);
	}

	public boolean answered() {
		return value != null;
	}
}
