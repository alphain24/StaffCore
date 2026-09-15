package io.github.alphain24.staffcore.api;

/**
 * What an action asked for from Discord did.
 *
 * @param done    whether anything changed
 * @param message what to show the Discord user, either way
 */
public record DiscordResult(boolean done, String message) {

	public static DiscordResult no(String why) {
		return new DiscordResult(false, why);
	}
}
