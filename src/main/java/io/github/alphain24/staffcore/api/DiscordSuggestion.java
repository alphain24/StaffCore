package io.github.alphain24.staffcore.api;

/**
 * One autocomplete choice: what is shown, and what is filled in.
 *
 * @param label up to 100 characters, what the person picking sees
 * @param value what the option is set to when it is picked
 */
public record DiscordSuggestion(String label, String value) {

	public DiscordSuggestion {
		label = label == null ? "" : label.length() > 100 ? label.substring(0, 99) + "\u2026" : label;
		value = value == null ? "" : value;
	}
}
