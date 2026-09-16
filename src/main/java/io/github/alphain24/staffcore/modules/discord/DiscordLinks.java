package io.github.alphain24.staffcore.modules.discord;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.util.ShortId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Which Discord account belongs to which Minecraft account.
 *
 * <h2>How a link is made</h2>
 * The Minecraft side goes first. A player who is signed in to the server asks for a code with
 * {@code /staff discord link}, and whoever types that code into Discord becomes linked to them.
 * Being signed in is the proof: the code can only have come from somebody holding the Minecraft
 * account, so a link can never be claimed from the Discord side alone — which is the direction
 * an attacker would try, since Discord names are free to choose.
 * <p>
 * Codes are single-use, last {@value #CODE_MINUTES} minutes, and are held in memory only: a
 * restart forgets them, which costs somebody a retyped command and means no table anywhere holds
 * a live credential. Guessing is capped per Discord account.
 *
 * <h2>Never deleted</h2>
 * Unlinking ends a row rather than removing it. Actions from Discord are attributed to both
 * accounts, and "which Minecraft account did this Discord user act as, last March" has to stay
 * answerable after they unlink, relink elsewhere, or leave.
 */
public final class DiscordLinks {

	public static final String TABLE = """
			CREATE TABLE IF NOT EXISTS discord_links (
			    id           INTEGER PRIMARY KEY AUTOINCREMENT,
			    discord_id   TEXT    NOT NULL,
			    discord_name TEXT,
			    uuid         TEXT    NOT NULL,
			    name         TEXT    NOT NULL,
			    linked_at    INTEGER NOT NULL,
			    ended_at     INTEGER,
			    ended_by     TEXT,
			    end_reason   TEXT
			)
			""";
	public static final String DISCORD_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_discord_links_discord ON discord_links(discord_id, ended_at)";
	public static final String PLAYER_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_discord_links_player ON discord_links(uuid, ended_at)";

	public static final int CODE_MINUTES = 10;
	static final int CODE_LENGTH = 8;

	/**
	 * Wrong codes one Discord account may try in {@value #FAILURE_WINDOW_MINUTES} minutes.
	 * <p>
	 * A code is forty bits and lives ten minutes, so guessing was never realistic; the cap is
	 * what keeps it that way against somebody patient, and it is per account so one person's
	 * typos do not lock out everybody else.
	 */
	static final int MAX_FAILURES = 5;
	static final int FAILURE_WINDOW_MINUTES = 10;

	/** An active link. */
	public record Link(long id, String discordId, String discordName, UUID playerId,
			String playerName, long linkedAt) {}

	/** What happened when somebody typed a code. */
	public record Redeemed(Link link, String refusal) {

		static Redeemed no(String why) {
			return new Redeemed(null, why);
		}

		public boolean linked() {
			return link != null;
		}
	}

	private record Pending(UUID playerId, String playerName, long expiresAt) {}

	private final LongSupplier clock;
	private final Map<String, Pending> codes = new ConcurrentHashMap<>();
	private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

	public DiscordLinks() {
		this(System::currentTimeMillis);
	}

	/** With a clock a test can move, so expiry is checked without waiting ten minutes. */
	DiscordLinks(LongSupplier clock) {
		this.clock = clock;
	}

	// ------------------------------------------------------------------ codes

	/**
	 * A fresh code for this player, replacing any they asked for before.
	 * <p>
	 * One live code per player, so a code left in chat history an hour ago is already dead by the
	 * time anybody finds it.
	 */
	public String issueCode(UUID player, String playerName) {
		codes.values().removeIf(p -> p.playerId().equals(player));
		String code;
		do {
			code = ShortId.generate(CODE_LENGTH);
		} while (codes.containsKey(code));
		codes.put(code, new Pending(player, playerName, clock.getAsLong() + CODE_MINUTES * 60_000L));
		return code;
	}

	/** The code printed in readable halves: {@code ABCD-EFGH}. Either form can be typed back. */
	public static String display(String code) {
		return code.length() == CODE_LENGTH ? code.substring(0, 4) + "-" + code.substring(4) : code;
	}

	/**
	 * Links whoever typed this code to whoever asked for it.
	 * <p>
	 * A Discord account already linked elsewhere is moved, and so is a Minecraft account already
	 * linked to a different Discord account: one each way, always, so an action from Discord has
	 * exactly one person behind it. Both old rows are ended, not removed.
	 */
	public Redeemed redeem(String discordId, String discordName, String typed) {
		if (discordId == null || discordId.isBlank()) return Redeemed.no("No Discord account.");

		long now = clock.getAsLong();
		Deque<Long> recent = failures.computeIfAbsent(discordId, k -> new ArrayDeque<>());
		synchronized (recent) {
			while (!recent.isEmpty() && now - recent.peekFirst() > FAILURE_WINDOW_MINUTES * 60_000L) {
				recent.pollFirst();
			}
			if (recent.size() >= MAX_FAILURES) {
				return Redeemed.no("Too many wrong codes. Wait " + FAILURE_WINDOW_MINUTES
						+ " minutes, then ask for a new one in game with /staff discord link.");
			}
		}

		String code = ShortId.normalise(typed, CODE_LENGTH);
		Pending pending = code == null ? null : codes.remove(code);
		if (pending == null || pending.expiresAt() < now) {
			synchronized (recent) {
				recent.addLast(now);
			}
			return Redeemed.no("That code is not valid. Codes last " + CODE_MINUTES + " minutes and "
					+ "work once; ask for a new one in game with /staff discord link.");
		}

		if (!StaffCore.storage().isReady()) {
			return Redeemed.no("Storage is unavailable, so nothing was linked.");
		}

		// Ending the old links and writing the new one together: a crash between the two would
		// otherwise leave a Discord account linked to two players, or to none with the code spent.
		long[] id = new long[1];
		boolean committed = StaffCore.storage().inTransaction(
				conn -> id[0] = write(conn, discordId, discordName, pending, now));
		if (!committed || id[0] <= 0) {
			return Redeemed.no("The link could not be saved, so nothing was linked.");
		}
		failures.remove(discordId);
		return new Redeemed(new Link(id[0], discordId, discordName, pending.playerId(),
				pending.playerName(), now), null);
	}

	private static long write(Connection c, String discordId, String discordName, Pending pending,
			long now) throws SQLException {

		try (PreparedStatement ps = c.prepareStatement("UPDATE discord_links SET ended_at=?, "
				+ "ended_by=?, end_reason=? WHERE ended_at IS NULL AND (discord_id=? OR uuid=?)")) {
			ps.setLong(1, now);
			ps.setString(2, pending.playerName());
			ps.setString(3, "replaced by a new link");
			ps.setString(4, discordId);
			ps.setString(5, pending.playerId().toString());
			ps.executeUpdate();
		}
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO discord_links "
				+ "(discord_id, discord_name, uuid, name, linked_at) VALUES (?,?,?,?,?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, discordId);
			ps.setString(2, discordName);
			ps.setString(3, pending.playerId().toString());
			ps.setString(4, pending.playerName());
			ps.setLong(5, now);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				return keys.next() ? keys.getLong(1) : -1;
			}
		}
	}

	// ------------------------------------------------------------------ reading

	/** The Minecraft account this Discord account acts as, or null. */
	public Link forDiscord(String discordId) {
		return one("SELECT * FROM discord_links WHERE discord_id=? AND ended_at IS NULL "
				+ "ORDER BY linked_at DESC LIMIT 1", discordId);
	}

	/** The Discord account linked to this player, or null. */
	public Link forPlayer(UUID player) {
		return player == null ? null
				: one("SELECT * FROM discord_links WHERE uuid=? AND ended_at IS NULL "
						+ "ORDER BY linked_at DESC LIMIT 1", player.toString());
	}

	/** Every active link, newest first, at most {@code limit}. For whoever administers permissions. */
	public List<Link> active(int limit) {
		Connection c = StaffCore.storage().conn();
		List<Link> out = new ArrayList<>();
		if (c == null || limit <= 0) return out;
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM discord_links WHERE ended_at IS NULL "
				+ "ORDER BY linked_at DESC LIMIT ?")) {
			ps.setInt(1, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					try {
						out.add(read(rs));
					} catch (IllegalArgumentException skipped) {
						// A row with a malformed id is somebody else's problem to find; the rest still list.
					}
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Discord] could not list links: {}", e.getMessage());
		}
		return out;
	}

	/** How many links are active. */
	public int activeCount() {
		Connection c = StaffCore.storage().conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM discord_links WHERE ended_at IS NULL");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Discord] could not count links: {}", e.getMessage());
			return 0;
		}
	}

	private Link one(String sql, String key) {
		Connection c = StaffCore.storage().conn();
		if (c == null || key == null) return null;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		} catch (SQLException | IllegalArgumentException e) {
			StaffCore.LOGGER.warn("[Discord] could not read a link: {}", e.getMessage());
			return null;
		}
	}

	private static Link read(ResultSet rs) throws SQLException {
		return new Link(rs.getLong("id"), rs.getString("discord_id"),
				rs.getString("discord_name"), UUID.fromString(rs.getString("uuid")),
				rs.getString("name"), rs.getLong("linked_at"));
	}

	// ------------------------------------------------------------------ ending

	/** Ends this Discord account's link. True when there was one. */
	public boolean endForDiscord(String discordId, String by, String reason) {
		return end("discord_id", discordId, by, reason);
	}

	/** Ends this player's link. True when there was one. */
	public boolean endForPlayer(UUID player, String by, String reason) {
		return player != null && end("uuid", player.toString(), by, reason);
	}

	private boolean end(String column, String key, String by, String reason) {
		Connection c = StaffCore.storage().conn();
		if (c == null || key == null) return false;
		try (PreparedStatement ps = c.prepareStatement("UPDATE discord_links SET ended_at=?, ended_by=?, "
				+ "end_reason=? WHERE " + column + "=? AND ended_at IS NULL")) {
			ps.setLong(1, clock.getAsLong());
			ps.setString(2, by);
			ps.setString(3, reason);
			ps.setString(4, key);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Discord] could not end a link: {}", e.getMessage());
			return false;
		}
	}
}
