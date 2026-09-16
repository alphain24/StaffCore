package io.github.alphain24.staffcore.modules.punish;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tells staff when somebody comes back after a ban.
 * <p>
 * A ban that runs out, or that somebody lifts, ends quietly: the player is simply allowed in next time
 * they try. The first join after that is the moment staff most want to know about — whether to keep an
 * eye on them, or to say welcome back — and nothing marked it. Each ban is noticed once, on the first
 * join after it ended, whenever that is.
 * <p>
 * Noticing is a state on the ban's row, {@code return_noticed_at}, never a separate record that could
 * be lost. Bans that had already ended when this was installed were marked noticed by the migration
 * that added it, so an upgrade does not announce every player who was ever banned.
 */
public final class ReturnWatch {
	private ReturnWatch() {}

	/**
	 * The stored names of every ban type, quoted for SQL.
	 * <p>
	 * Written out rather than read from {@link PunishmentType}, because the migration that uses it runs
	 * before anything else is loaded and the enum carries items with it. {@code ReturnWatchTypesTest}
	 * fails if the two disagree.
	 */
	public static final String BAN_TYPES = "'BAN','TEMPBAN'";

	/** Migration 31's backfill: every ban already over counts as noticed. */
	public static String backfill(long now) {
		return "UPDATE punishments SET return_noticed_at = " + now + " WHERE return_noticed_at IS NULL AND type IN ("
				+ BAN_TYPES + ") AND (active = 0 OR (expires_at IS NOT NULL AND expires_at <= " + now + "))";
	}

	/** What {@link #BAN_TYPES} should say, from the enum. */
	static String banTypesFromEnum() {
		return Arrays.stream(PunishmentType.values()).filter(PunishmentType::isBan)
				.map(t -> "'" + t.name() + "'").collect(Collectors.joining(","));
	}

	/** How a ban ended, for the line staff read. */
	public record Ended(Punishment ban, long endedAt, boolean lifted) {

		/** "expired 2 days ago" or "lifted by Mod 3 hours ago: appeal #4 accepted". */
		public String describe() {
			if (!lifted) return "expired " + TimeFormat.words(endedAt);
			String reason = ban.revokeReason();
			return "lifted by " + (ban.revokedBy() == null ? "staff" : ban.revokedBy()) + " "
					+ TimeFormat.words(endedAt) + (reason == null || reason.isBlank() ? "" : ": " + reason);
		}
	}

	/**
	 * Called as a player joins. Announces the most recent of their bans that has ended and not been
	 * noticed, and marks every such ban noticed, so a second join says nothing.
	 *
	 * @return what was announced, or null when there was nothing to
	 */
	public static Ended onJoin(MinecraftServer server, ServerPlayer player) {
		Connection c = conn();
		if (c == null) return null;

		long now = System.currentTimeMillis();
		List<Punishment> ended = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement("SELECT * FROM punishments WHERE target_uuid=? AND type IN ("
				+ BAN_TYPES + ") AND return_noticed_at IS NULL AND (active = 0 OR (expires_at IS NOT NULL "
				+ "AND expires_at <= ?)) ORDER BY id DESC")) {
			ps.setString(1, player.getUUID().toString());
			ps.setLong(2, now);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) ended.add(Mods.punish().map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Punish] could not check {} for ended bans: {}", Mc.name(player), e.getMessage());
			return null;
		}
		if (ended.isEmpty()) return null;

		// Claimed before anything is said: two joins in quick succession, or two servers on one file,
		// must not both announce it.
		int claimed = 0;
		for (Punishment p : ended) {
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE punishments SET return_noticed_at=? WHERE id=? AND return_noticed_at IS NULL")) {
				ps.setLong(1, now);
				ps.setLong(2, p.id());
				claimed += ps.executeUpdate();
			} catch (SQLException e) {
				StaffCore.LOGGER.warn("[Punish] could not mark ban {} noticed: {}", p.id(), e.getMessage());
			}
		}
		// Switched off, the bans are still marked, so switching it back on does not replay them.
		if (claimed == 0 || !StaffConfig.get().notifyReturningPlayers) return null;

		Punishment latest = ended.get(0);
		boolean lifted = latest.revokedAt() != null || latest.revokedBy() != null;
		long endedAt = lifted && latest.revokedAt() != null ? latest.revokedAt()
				: latest.expiresAt() != null ? latest.expiresAt() : latest.createdAt();
		Ended what = new Ended(latest, endedAt, lifted);
		String name = Mc.name(player);

		Mods.alerts().onReturn(server, "%s is back for the first time since their %s ended — %s (#%d: %s)".formatted(
				name, latest.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '), what.describe(),
				latest.id(), latest.reasonOr("no reason given")));
		if (latest.hasCase()) {
			Mods.cases().store().note(latest.caseId(), Case.SYSTEM, name + " joined for the first time since "
					+ latest.type().name().toLowerCase(java.util.Locale.ROOT) + " #" + latest.id() + " ended ("
					+ what.describe() + ")");
		}
		StaffCoreApi.publish(new StaffCoreEvent.PlayerReturned(now, player.getUUID(), name, latest.id(),
				latest.type().name(), latest.reason(), endedAt, lifted ? "LIFTED" : "EXPIRED",
				lifted ? latest.revokedBy() : null, lifted ? latest.revokeReason() : null, latest.caseId()));
		return what;
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
