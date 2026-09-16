package io.github.alphain24.staffcore.discord.channels;

import java.util.List;

/**
 * Something to do in Discord, decided without Discord.
 * <p>
 * The router turns StaffCore's events into these; the gateway carries them out, one at a time and
 * in order, on the companion's own thread. Keeping the decision apart from the doing is what lets
 * a test say "a report opens a thread and a claim edits the same message" without a bot.
 */
public sealed interface Outbound {

	/** Which of the configured channels. */
	enum Channel {
		PUNISHMENTS, REPORTS, ALERTS, APPEALS, STAFF_LOG, STAFF_CHAT,
		/** Not posted to: the punishment panel's private channel. */
		PUNISH_PANEL
	}

	/** A button on a message. Its id tells the gateway what was clicked. */
	record Button(String id, String label, Style style, boolean disabled) {

		public enum Style { PRIMARY, SECONDARY, SUCCESS, DANGER }

		public Button disable() {
			return new Button(id, label, style, true);
		}
	}

	/**
	 * What a message holds.
	 *
	 * @param content plain text, already escaped, or null
	 * @param embed   the embed, or null
	 * @param rows    rows of buttons, at most five in a row
	 */
	record Message(String content, Embed embed, List<List<Button>> rows) {

		public Message {
			rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
		}

		public static Message text(String content) {
			return new Message(content, null, List.of());
		}

		public static Message of(Embed embed) {
			return new Message(null, embed, List.of());
		}

		public Message withEmbed(Embed changed) {
			return new Message(content, changed, rows);
		}

		public Message withRows(List<List<Button>> changed) {
			return new Message(content, embed, changed);
		}
	}

	/**
	 * Posts a message.
	 *
	 * @param key        remembered under this, so it can be edited or its thread found; or null
	 * @param threadName start a thread on it with this name; or null
	 * @param sharing    other keys that should find the same thread — a case opened by a report
	 *                   is discussed in the report's thread
	 */
	record Send(Channel channel, Message message, String key, String threadName, List<String> sharing)
			implements Outbound {

		public Send {
			sharing = sharing == null ? List.of() : List.copyOf(sharing);
		}
	}

	/** Replaces the message remembered under this key. Nothing happens if there is none. */
	record Update(String key, Message message) implements Outbound {}

	/**
	 * A direct message to one Discord account: the player behind an appeal.
	 *
	 * @param userId      the account, as an id
	 * @param fallbackKey where to say so when the message cannot be delivered — a player with direct
	 *                    messages off never hears, and staff need to know that; or null
	 */
	record Direct(String userId, Message message, String fallbackKey) implements Outbound {}

	/**
	 * A line in the thread remembered under this key.
	 *
	 * @param archive close the thread afterwards, because what it was about is finished
	 */
	record InThread(String key, String text, boolean archive) implements Outbound {}
}
