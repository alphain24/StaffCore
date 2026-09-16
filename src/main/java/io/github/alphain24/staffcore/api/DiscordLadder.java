package io.github.alphain24.staffcore.api;

import java.util.List;
import java.util.UUID;

/**
 * A player, and what each offence ladder would do to them now: the punishment panel's view.
 *
 * @param activeBan  the ban in force, described, or null
 * @param activeMute the mute in force, described, or null
 * @param rungs      every offence the server has, in the order the game lists them
 */
public record DiscordLadder(UUID playerId, String playerName, int punishments, String activeBan,
		String activeMute, List<Rung> rungs) {

	public DiscordLadder {
		rungs = rungs == null ? List.of() : List.copyOf(rungs);
	}

	/**
	 * One offence, as it stands for this player.
	 *
	 * @param priors  how many times they have been punished for it before
	 * @param applies what it would do now, such as "Ban · 3d"
	 * @param type    the punishment type that would be issued
	 * @param next    what it would do the time after, or null at the top of the ladder
	 * @param allowed whether the person asking may issue {@code type} from Discord
	 */
	public record Rung(String offenceId, String label, String description, int priors, String applies, String type,
			String next, boolean allowed) {}
}
