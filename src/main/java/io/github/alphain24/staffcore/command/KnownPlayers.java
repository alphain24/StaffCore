package io.github.alphain24.staffcore.command;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.util.PlayerLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Everyone the server has ever seen, for completing and for resolving a typed name.
 * <p>
 * Staff work on people who are not here. That is most of the job: somebody logs off and the
 * report about them arrives ten minutes later, and a tool that can only name the players
 * currently standing in the world is a tool that cannot do the thing it exists for. Vanilla's
 * completion offers the online list, so the name staff need is the one they have to type from
 * memory — which is where the spelling goes wrong.
 *
 * <h2>Why refusing beats guessing</h2>
 * {@code Steve_} and {@code Steve__} is how the wrong person gets banned, and it is not a
 * hypothetical: a name one underscore different from a real player is a standard impersonation
 * trick, so the two accounts are frequently both real, both present, and one of them is the
 * one being reported. Picking the closer match would be right most of the time, and the times
 * it is wrong are exactly the times somebody set it up to be wrong.
 * <p>
 * So a prefix matching more than one known name is refused, with the candidates listed as
 * things to click. One extra keystroke against banning the wrong account is not a trade worth
 * thinking about.
 */
public final class KnownPlayers {
	private KnownPlayers() {}

	/** Enough to choose from; not so many that the list is its own problem. */
	private static final int SUGGESTION_LIMIT = 40;

	/**
	 * What a typed name turned out to mean.
	 *
	 * @param profile    the one player it names, or null
	 * @param candidates every known name it could have meant, when there is more than one
	 */
	public record Match(NameAndId profile, List<String> candidates) {

		public boolean isResolved() {
			return profile != null;
		}

		public boolean isAmbiguous() {
			return profile == null && !candidates.isEmpty();
		}

		static Match of(NameAndId profile) {
			return new Match(profile, List.of());
		}

		static Match ambiguous(List<String> candidates) {
			return new Match(null, candidates);
		}

		static final Match UNKNOWN = new Match(null, List.of());
	}

	/**
	 * Resolves a typed name to exactly one player, or explains why it could not.
	 * <p>
	 * Exact match first, and case-insensitively, because that is what somebody typing a name
	 * they already know means and it must never be treated as ambiguous. Only a name that
	 * matches nothing exactly is treated as a prefix.
	 */
	public static Match resolve(MinecraftServer server, String typed) {
		if (server == null || typed == null || typed.isBlank()) return Match.UNKNOWN;
		String name = typed.trim();

		// An exact name is an answer, even when longer names start with it. Refusing to act
		// on "Steve_" because "Steve__" also exists would make the shorter name unusable.
		Optional<NameAndId> exact = PlayerLookup.profile(server, name);
		if (exact.isPresent()) return Match.of(exact.get());

		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (online.nameAndId().name().equalsIgnoreCase(name)) {
				return Match.of(online.nameAndId());
			}
		}

		List<String> candidates = startingWith(server, name, SUGGESTION_LIMIT);
		if (candidates.size() == 1) {
			// One prefix match is not a guess between alternatives; it is the only thing it
			// could have been. Still resolved through the name cache so the id is real.
			return PlayerLookup.profile(server, candidates.get(0))
					.map(Match::of).orElse(Match.UNKNOWN);
		}
		return candidates.isEmpty() ? Match.UNKNOWN : Match.ambiguous(candidates);
	}

	/**
	 * Known names beginning with a prefix, for completion and for the ambiguity list.
	 * <p>
	 * Three sources, in the order that matters. Online players first because they are the
	 * most likely target and the most current spelling. Then the connections table, which is
	 * everyone who has joined inside the retention window. Then punishment targets, which
	 * catches somebody punished by name while offline and never seen since — the case where
	 * staff most need the name and are least likely to remember it.
	 */
	public static List<String> startingWith(MinecraftServer server, String prefix, int limit) {
		String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		Set<String> names = new LinkedHashSet<>();

		if (server != null) {
			for (ServerPlayer online : server.getPlayerList().getPlayers()) {
				String name = online.nameAndId().name();
				if (name.toLowerCase(Locale.ROOT).startsWith(lower)) names.add(name);
			}
		}

		addFromDatabase(names, "SELECT DISTINCT name FROM connections "
				+ "WHERE LOWER(name) LIKE ? ESCAPE '!' ORDER BY name LIMIT ?", lower, limit);
		addFromDatabase(names, "SELECT DISTINCT target_name FROM punishments "
				+ "WHERE LOWER(target_name) LIKE ? ESCAPE '!' ORDER BY target_name LIMIT ?",
				lower, limit);

		List<String> out = new ArrayList<>(names);
		return out.size() > limit ? out.subList(0, limit) : out;
	}

	private static void addFromDatabase(Set<String> into, String sql, String lowerPrefix,
			int limit) {

		if (!StaffCore.storage().isReady() || into.size() >= limit) return;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(sql)) {
			// LIKE with an escaped prefix. A name containing % or _ is unusual and legal, and
			// left unescaped it would quietly match half the server.
			ps.setString(1, escapeLike(lowerPrefix) + "%");
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next() && into.size() < limit) {
					String name = rs.getString(1);
					if (name != null && !name.isBlank()) into.add(name);
				}
			}
		} catch (SQLException e) {
			// Completion is a convenience. Losing it should cost the suggestions, not the
			// command somebody is in the middle of typing.
			StaffCore.LOGGER.debug("[Commands] name completion query failed: {}", e.getMessage());
		}
	}

	/** SQLite has no default LIKE escape character, so one is declared in the query. */
	private static String escapeLike(String s) {
		return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
