package dev.lebron.staffcore.modules.anticheat;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.config.StaffConfig;
import dev.lebron.staffcore.module.Module;
import dev.lebron.staffcore.module.Mods;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where anti-cheat findings arrive and become staff-visible.
 * <p>
 * An anti-cheat that shouts into console is a tool nobody uses. The findings that matter are
 * the ones a staff member sees next to everything else they already know about that player —
 * the punishment history, the alt links, the mining report — because a flag on its own is
 * ambiguous and a flag alongside three others is not.
 * <p>
 * This module owns the receiving end only. It never decides that somebody is cheating; it
 * records what a provider decided, alerts on it, and keeps it where it can be read later.
 * Punishing on the strength of a detection stays a human action, on purpose.
 */
public final class AntiCheatModule implements Module {

	@Override
	public String id() {
		return "anticheat";
	}

	@Override
	public String displayName() {
		return "Anti-cheat bridge";
	}

	private final List<String> attached = new ArrayList<>();

	@Override
	public void onEnable() {
		attached.clear();
		attached.addAll(AntiCheatProviders.attachAll(this));
		purge();
	}

	/**
	 * Drops findings past the retention window.
	 * <p>
	 * Anti-cheats are the chattiest source of rows in the whole database — a busy server can
	 * produce thousands a day — and a year-old flag on a player who has been fine since is
	 * not evidence of anything. Kept short by default for that reason.
	 */
	public void purge() {
		int days = StaffConfig.get().antiCheatRetentionDays;
		Connection c = conn();
		if (days <= 0 || c == null) return;

		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM anticheat_log WHERE created_at < ?")) {
			ps.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
			int gone = ps.executeUpdate();
			if (gone > 0) {
				StaffCore.LOGGER.info("[AntiCheat] Purged {} finding(s) older than {} days",
						gone, days);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[AntiCheat] purge failed", e);
		}
	}

	/** Which providers are actually wired up right now, for {@code /staff status}. */
	public List<String> attachedProviders() {
		return List.copyOf(attached);
	}

	// -------------------------------------------------------------------- receiving

	/**
	 * The one entry point. Any provider, adapter or admin script calls this.
	 * <p>
	 * Deliberately tolerant: a malformed event is dropped with a warning rather than thrown
	 * back at the caller, because the caller is usually another mod's event thread and an
	 * exception there becomes somebody else's crash report.
	 */
	public void report(AntiCheatEvent event) {
		if (event == null) return;

		StaffConfig cfg = StaffConfig.get();
		if (!cfg.antiCheatBridge) return;

		if (event.playerName() == null || event.playerName().isBlank()) {
			StaffCore.LOGGER.warn("[AntiCheat] {} sent an event with no player - dropped",
					event.provider());
			return;
		}

		MinecraftServer server = StaffCore.server();
		if (server == null) return;

		store(event);

		// Below the floor it is recorded but not shouted about. Anti-cheats are chatty by
		// design — most flag on a threshold that expects to be wrong sometimes — and an alert
		// channel that cries wolf is one staff learn to scroll past. Mitigations and
		// punishments always alert: those already happened to a player.
		if (event.hasConfidence() && event.confidence() < cfg.antiCheatAlertConfidence
				&& !event.kind().isAction()) {
			return;
		}

		Mods.alerts().onSecurityFlag(server, event.playerName(),
				"[" + event.provider() + "] " + event.headline());
	}

	/** Convenience for adapters that only have a name to work with. */
	public void report(String provider, String playerName, AntiCheatEvent.Kind kind,
			String check, int confidence, String detail) {

		MinecraftServer server = StaffCore.server();
		UUID id = null;
		if (server != null) {
			ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
			id = online != null
					? online.getUUID()
					: dev.lebron.staffcore.util.PlayerLookup.uuid(server, playerName).orElse(null);
		}
		report(new AntiCheatEvent(provider, id, playerName, kind, check, confidence, detail,
				System.currentTimeMillis()));
	}

	// --------------------------------------------------------------------- reading

	/** Everything one player has been flagged for, newest first. */
	public List<AntiCheatEvent> forPlayer(String playerName, int limit) {
		return query("SELECT * FROM anticheat_log WHERE player_name = ? "
				+ "ORDER BY created_at DESC LIMIT ?", ps -> {
			ps.setString(1, playerName);
			ps.setInt(2, limit);
		});
	}

	/** The most recent findings across everybody. */
	public List<AntiCheatEvent> recent(int offset, int limit) {
		return query("SELECT * FROM anticheat_log ORDER BY created_at DESC LIMIT ? OFFSET ?",
				ps -> {
					ps.setInt(1, limit);
					ps.setInt(2, offset);
				});
	}

	public int count() {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM anticheat_log");
				ResultSet rs = ps.executeQuery()) {
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) {
			return 0;
		}
	}

	public int countFor(String playerName) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM anticheat_log WHERE player_name = ?")) {
			ps.setString(1, playerName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** How many of each check a player has tripped, biggest first. */
	public Map<String, Integer> checkTally(String playerName, int limit) {
		Map<String, Integer> raw = new java.util.HashMap<>();
		Connection c = conn();
		if (c == null) return Map.of();

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT check_name, COUNT(*) FROM anticheat_log WHERE player_name = ? "
						+ "GROUP BY check_name ORDER BY COUNT(*) DESC LIMIT ?")) {
			ps.setString(1, playerName);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) raw.put(rs.getString(1), rs.getInt(2));
			}
		} catch (SQLException e) {
			return Map.of();
		}

		LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
		raw.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	// -------------------------------------------------------------------- plumbing

	private void store(AntiCheatEvent event) {
		Connection c = conn();
		if (c == null) return;

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO anticheat_log "
						+ "(provider, uuid, player_name, kind, check_name, confidence, detail, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,?)")) {

			ps.setString(1, event.provider());
			if (event.playerId() == null) ps.setNull(2, java.sql.Types.VARCHAR);
			else ps.setString(2, event.playerId().toString());
			ps.setString(3, event.playerName());
			ps.setString(4, event.kind().name());
			ps.setString(5, event.check());
			ps.setInt(6, event.confidence());
			ps.setString(7, event.detail());
			ps.setLong(8, event.at());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[AntiCheat] Could not record a finding", e);
		}
	}

	private interface Binder {
		void bind(PreparedStatement ps) throws SQLException;
	}

	private List<AntiCheatEvent> query(String sql, Binder binder) {
		List<AntiCheatEvent> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					AntiCheatEvent.Kind kind;
					try {
						kind = AntiCheatEvent.Kind.valueOf(rs.getString("kind"));
					} catch (IllegalArgumentException unknown) {
						// A row written by a newer version. Reading it as a plain detection
						// is a worse label, not a lost row.
						kind = AntiCheatEvent.Kind.DETECTION;
					}
					String uuid = rs.getString("uuid");
					out.add(new AntiCheatEvent(
							rs.getString("provider"),
							uuid == null ? null : UUID.fromString(uuid),
							rs.getString("player_name"),
							kind,
							rs.getString("check_name"),
							rs.getInt("confidence"),
							rs.getString("detail"),
							rs.getLong("created_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[AntiCheat] Query failed", e);
		}
		return out;
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
