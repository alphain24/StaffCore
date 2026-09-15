package io.github.alphain24.staffcore.api;

import java.util.List;
import java.util.UUID;

/**
 * A player's standing, as a Discord user with the profile permission may read it.
 * <p>
 * Conduct, not identity. Nothing drawn from where a player connects from is here — not their
 * address, and not the accounts linked to them through it, which are a lead for staff in game and
 * a disclosure anywhere else.
 *
 * @param activeBan    the ban in force, or null
 * @param activeMute   the mute in force, or null
 * @param openCases    ids of the player's open cases
 */
public record DiscordProfile(UUID id, String name, boolean online, int punishments, int warningPoints,
		int notes, int openAppeals, DiscordPunishment activeBan, DiscordPunishment activeMute,
		List<String> openCases) {

	public DiscordProfile {
		openCases = openCases == null ? List.of() : List.copyOf(openCases);
	}
}
