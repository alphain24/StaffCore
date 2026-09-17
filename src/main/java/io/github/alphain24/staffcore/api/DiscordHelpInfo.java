package io.github.alphain24.staffcore.api;

import java.util.UUID;

/**
 * What StaffCore knows about the player somebody asked staff for help as, from the public contact channel.
 * <p>
 * The name was typed by whoever pressed the button, so it proves nothing on its own; {@link #linkedTo} is
 * what says whether the Discord account really belongs to that player.
 *
 * @param playerId   null when nobody of that name has joined this server
 * @param playerName the name as the server knows it, or as typed when it knows none
 * @param linkedTo   the Minecraft name the asking Discord account is linked to, or null
 * @param online     whether the player is on the server now
 * @param frozen     whether they are frozen, online or not
 * @param banned     whether a ban is in force
 * @param caseId     their newest open case, or null
 */
public record DiscordHelpInfo(UUID playerId, String playerName, String linkedTo, boolean online, boolean frozen,
		boolean banned, String caseId) {

	public boolean known() {
		return playerId != null;
	}

	/** Whether the account that asked is linked to the player it named. */
	public boolean linkedToThisPlayer() {
		return linkedTo != null && linkedTo.equalsIgnoreCase(playerName);
	}
}
