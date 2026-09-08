package io.github.alphain24.staffcore.modules.punish;

import net.minecraft.server.players.NameAndId;
import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Sfx;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.modules.alerts.AlertsModule;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records punishments, enforces them, and announces them.
 * <p>
 * {@link #apply} is the single entry point — the GUI, the flat commands and any future
 * automation all funnel through it, so there is exactly one place where a punishment is
 * written, enforced, broadcast and mirrored to Discord. Nothing else in the mod calls
 * {@code disconnect} on a punished player.
 */
public class PunishmentModule implements Module {

	@Override
	public String id() {
		return "punishment";
	}

	@Override
	public String displayName() {
		return "Punishments";
	}

	// ------------------------------------------------------------------ the one door

	/**
	 * Applies a punishment end to end.
	 *
	 * @param base       WARN / KICK / MUTE / BAN — the temporary variant is derived from {@code durationMs}
	 * @param durationMs null for permanent (or for types that have no duration)
	 * @return the stored record, or null when storage rejected it
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason) {
		return apply(server, target, staffName, base, durationMs, reason, null, null);
	}

	/** As above, tagged with the offence ladder it came from so escalation can count it. */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId) {
		return apply(server, target, staffName, base, durationMs, reason, offenceId, null);
	}

	/**
	 * As above, attached to the case it came out of.
	 * <p>
	 * Still the only way anybody gets punished. The case is optional and stays optional: a
	 * punishment issued directly records null rather than being refused, because a staff
	 * member watching somebody grief in front of them should not have to open a case first.
	 * What matters is that the null is <em>recorded</em> and shown, so "how often do we punish
	 * without evidence attached" is a question with an answer.
	 *
	 * @param caseId the case this came from, or null when issued directly
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId, String caseId) {

		return apply(server, target, staffName, base, durationMs, reason, offenceId, caseId,
				server == null ? null : server.getPlayerList().getPlayerByName(staffName));
	}

	/**
	 * The one door. Every punishment in the mod arrives here.
	 * <p>
	 * The rate limit is checked <em>here</em> rather than in the command, and that placement
	 * is the point: a check in {@code /staff ban} would leave the GUI, the API and any future
	 * Discord path unlimited, each of them a way in somebody would have to remember to close.
	 * A new caller gets the limit without being told about it.
	 *
	 * @param actor the staff member acting, for the rate limit and the audit. Null for the
	 *              console, which is not the threat this guards against
	 */
	public Punishment apply(MinecraftServer server, NameAndId target, String staffName,
			PunishmentType base, Long durationMs, String reason, String offenceId, String caseId,
			ServerPlayer actor) {

		var verdict = Mods.accountability().limits().check(
				io.github.alphain24.staffcore.permission.Actor.of(actor),
				io.github.alphain24.staffcore.modules.accountability.RateLimits.Kind.PUNISHMENT);
		if (!verdict.allowed()) {
			if (actor != null) actor.sendSystemMessage(Theme.bad(verdict.refusal()));
			StaffCore.LOGGER.warn("[Punish] rate limit refused {} punishing {}",
					staffName, target.name());
			return null;
		}

		PunishmentType type = base.withDuration(durationMs);
		Long expiresAt = durationMs == null ? null : System.currentTimeMillis() + durationMs;
		String cleanReason = (reason == null || reason.isBlank()) ? "No reason given" : reason.trim();

		Punishment record = record(target.id(), target.name(), staffName, type,
				cleanReason, expiresAt, offenceId, caseId);
		if (record == null) return null;

		// Linked from both ends. The punishment row says which case it came from; the case
		// gets a link and an event, so its log reads as the story of what was done rather
		// than needing a join to find out.
		if (record.hasCase()) {
			var cases = Mods.cases().store();
			cases.link(caseId, "punishment", String.valueOf(record.id()), staffName);
			cases.note(caseId, staffName,
					type.name().toLowerCase(java.util.Locale.ROOT) + " issued: " + cleanReason);
		}

		enforce(server, record);
		announce(server, record);

		if (type == PunishmentType.WARN) suggestEscalation(server, record, actor);
		return record;
	}

	/**
	 * Tells the staff member the ladder thinks this player has crossed a line.
	 * <p>
	 * A suggestion, and it stays one. Nothing here calls {@link #apply} — it prints a command
	 * the staff member can click, with the reason pre-filled, and stops. An automatic
	 * escalation fires on a count rather than a judgement, and the case where a count is most
	 * likely to be wrong is a player being warned repeatedly by one staff member with a
	 * grudge, which is exactly where a human in the loop is the only safeguard.
	 * <p>
	 * Shown only to whoever issued the warning. Broadcasting it to staff chat would turn a
	 * private prompt into pressure to act.
	 */
	private void suggestEscalation(MinecraftServer server, Punishment warning, ServerPlayer actor) {
		if (actor == null) return;

		var standing = WarningPoints.standingOf(warning.targetUuid());
		if (!standing.escalates()) return;

		actor.sendSystemMessage(Theme.warn("%s is at %d/%d warning points — %s."
				.formatted(warning.targetName(), standing.points(), standing.threshold(),
						standing.reason())));

		String suggestion = "/staff %s %s %s".formatted(
				standing.suggested().name().toLowerCase(java.util.Locale.ROOT),
				warning.targetName(),
				"repeated warnings (" + standing.points() + " points)");

		actor.sendSystemMessage(io.github.alphain24.staffcore.gui.Icon.text("  ", Theme.MUTED)
				.append(io.github.alphain24.staffcore.gui.Link.suggest(
						"[" + standing.suggested().name().toLowerCase(java.util.Locale.ROOT) + "]",
						suggestion, Theme.ACCENT,
						"Fills in the command. Nothing happens until you send it.")));
		actor.sendSystemMessage(io.github.alphain24.staffcore.gui.Icon.text(
				"  A suggestion, not a rule. Ignore it if it does not fit.", Theme.MUTED));
	}

	/** How many times this player has already been done for this offence. */
	public int countForOffence(UUID target, String offenceId) {
		Connection c = conn();
		if (c == null || offenceId == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid=? AND offence=?")) {
			ps.setString(1, target.toString());
			ps.setString(2, offenceId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** Applies the immediate, in-world consequence. */
	private void enforce(MinecraftServer server, Punishment p) {
		ServerPlayer online = server.getPlayerList().getPlayer(p.targetUuid());

		if (p.type().isBan() || p.type() == PunishmentType.KICK) {
			if (online != null) {
				Mc.disconnect(online, disconnectScreen(p));
			}
			return;
		}

		if (online == null) return;

		if (p.type().isMute()) {
			online.sendSystemMessage(Theme.bad("You have been muted — " + p.reason()));
			online.sendSystemMessage(Theme.warn("Expires: " + p.remaining()));
			if (StaffConfig.get().allowInGameAppeals) {
				online.sendSystemMessage(Theme.info("Disagree? Use /appeal <what you want to say>."));
			}
			String invite = StaffConfig.get().discordInvite;
			if (invite != null && !invite.isBlank()) {
				online.sendSystemMessage(Theme.info("Or appeal on Discord: " + invite));
			}
			Sfx.muted(online);
		} else if (p.type() == PunishmentType.WARN) {
			online.sendSystemMessage(Theme.warn("Warning from " + p.staffName() + " — " + p.reason()));
			Sfx.warned(online);
		}
	}

	/**
	 * The full-screen text a banned or kicked player sees.
	 * <p>
	 * The appeal line matters more than it looks. A ban screen that only says "you are
	 * banned" produces a player who either gives up or comes back on an alt; one that says
	 * where to argue produces an appeal, which is a conversation staff can actually resolve.
	 */
	public Component disconnectScreen(Punishment p) {
		MutableComponent out = Icon.text(p.type().isBan() ? "You are banned\n\n" : "You were kicked\n\n", Theme.BAD);
		out.append(Icon.text(p.reason() + "\n", Theme.TEXT));
		out.append(Icon.text("By " + p.staffName() + "\n", Theme.MUTED));

		if (p.type().isBan()) {
			out.append(Icon.text(p.isPermanent()
					? "This ban does not expire.\n"
					: "Expires in " + TimeFormat.remaining(p.expiresAt()) + "\n", Theme.MUTED));
			out.append(appealBlock());
		}
		return out;
	}

	/**
	 * The "you can argue about this" half of a ban screen.
	 * <p>
	 * Always shown, even with no Discord invite configured, because the failure mode of
	 * staying silent is exactly the thing a ban is meant to prevent: a player who concludes
	 * there is no way back and simply returns on another account. With an invite set they
	 * get a destination; without one they are at least told an appeal exists.
	 */
	private MutableComponent appealBlock() {
		MutableComponent out = Icon.text("\n", Theme.MUTED);
		out.append(Icon.text("Think this is a mistake? You can appeal.\n", Theme.TEXT));

		String invite = StaffConfig.get().discordInvite;
		if (invite != null && !invite.isBlank()) {
			out.append(Icon.text("Join our Discord and open a ban appeal:\n", Theme.MUTED));
			out.append(Icon.text(invite, Theme.ACCENT));
		} else {
			out.append(Icon.text("Contact a staff member to open an appeal.", Theme.MUTED));
		}
		return out;
	}

	/** Staff chat line, alert bus, Discord, and the thunderclap for bans. */
	private void announce(MinecraftServer server, Punishment p) {
		MutableComponent line = Theme.prefix()
				.append(Icon.text(p.staffName(), Theme.ACCENT))
				.append(Icon.text(" " + p.type().pastTense() + " ", Theme.MUTED))
				.append(Icon.text(p.targetName(), Theme.TEXT))
				.append(Icon.text(" — " + p.reason(), Theme.MUTED));

		boolean everyone = StaffConfig.get().publicPunishmentBroadcast && p.type().persistent();
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			boolean isStaff = Permissions.check(viewer, Nodes.PUNISH);
			if (everyone || isStaff) {
				viewer.sendSystemMessage(line);
			}
		}

		if (p.type().isBan()) {
			Sfx.banBroadcast(server, viewer -> Permissions.check(viewer, Nodes.PUNISH));
		}

		StaffCore.modules().get("alerts", AlertsModule.class).ifPresent(a ->
				a.onPunishment(server, p.staffName(), p.targetName(), p.type(), p.reason()));
	}

	// -------------------------------------------------------------------- revoking

	/** Lifts an active ban or mute. Returns the number of rows affected. */
	public int revoke(MinecraftServer server, UUID target, String staffName, boolean bans) {
		return revoke(server, target, staffName, bans, null);
	}

	/**
	 * Lifts an active ban or mute, on stated grounds.
	 * <p>
	 * <b>Never deletes.</b> The row is marked reversed with who, when and why, and stays
	 * exactly where it was. A punishment that disappears on reversal takes the history with
	 * it: the player's record silently improves, an appeal that was upheld leaves no trace of
	 * having been upheld, and "has this happened before" quietly starts returning the wrong
	 * answer.
	 * <p>
	 * The linked case, if there is one, gets an event for the reversal as well as for the
	 * punishment — so its log reads as both halves of what happened rather than only the part
	 * that stuck.
	 *
	 * @return the number of punishments lifted
	 */
	public int revoke(MinecraftServer server, UUID target, String staffName, boolean bans,
			String reason) {

		String types = bans ? "('BAN','TEMPBAN')" : "('MUTE','TEMPMUTE')";
		Connection c = conn();
		if (c == null) return 0;

		// Read before writing, so the case events can name what was actually lifted.
		List<Punishment> lifting = activeOfTypes(target, types);

		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE punishments SET active=0, revoked_by=?, revoked_at=?, revoke_reason=? "
						+ "WHERE target_uuid=? AND active=1 AND type IN " + types)) {
			ps.setString(1, staffName);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, reason);
			ps.setString(4, target.toString());
			int n = ps.executeUpdate();

			for (Punishment lifted : lifting) {
				if (!lifted.hasCase()) continue;
				Mods.cases().store().note(lifted.caseId(), staffName,
						lifted.type().name().toLowerCase(java.util.Locale.ROOT) + " #"
								+ lifted.id() + " reversed"
								+ (reason == null || reason.isBlank() ? "" : ": " + reason));
			}

			if (n > 0 && !bans) {
				ServerPlayer online = server.getPlayerList().getPlayer(target);
				if (online != null) {
					online.sendSystemMessage(Theme.good("You have been unmuted."));
					Sfx.success(online);
				}
			}
			return n;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] revoke failed", e);
			return 0;
		}
	}

	// -------------------------------------------------------------------- querying

	/** Active ban for this uuid, or null. Expired rows are retired as a side effect. */
	public Punishment activeBan(UUID target) {
		return activeOfTypes(target, PunishmentType.BAN, PunishmentType.TEMPBAN);
	}

	/** Active mute for this uuid, or null. */
	public Punishment activeMute(UUID target) {
		return activeOfTypes(target, PunishmentType.MUTE, PunishmentType.TEMPMUTE);
	}

	private Punishment activeOfTypes(UUID target, PunishmentType a, PunishmentType b) {
		Connection c = conn();
		if (c == null) return null;

		String sql = "SELECT * FROM punishments WHERE target_uuid=? AND active=1 "
				+ "AND type IN (?,?) ORDER BY created_at DESC LIMIT 1";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, target.toString());
			ps.setString(2, a.name());
			ps.setString(3, b.name());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				Punishment p = map(rs);
				if (p.isExpired()) {
					deactivate(p.id());
					return null;
				}
				return p;
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] lookup failed", e);
			return null;
		}
	}

	public List<Punishment> history(UUID target) {
		List<Punishment> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE target_uuid=? ORDER BY created_at DESC")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] history failed", e);
		}
		return out;
	}

	public int historyCount(UUID target) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE target_uuid=?")) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	// -------------------------------------------------------------------- writing

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt) {
		return record(target, targetName, staffName, type, reason, expiresAt, null);
	}

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt, String offenceId) {
		return record(target, targetName, staffName, type, reason, expiresAt, offenceId, null);
	}

	public Punishment record(UUID target, String targetName, String staffName,
			PunishmentType type, String reason, Long expiresAt, String offenceId, String caseId) {
		Connection c = conn();
		if (c == null) return null;

		String sql = """
				INSERT INTO punishments
				  (target_uuid, target_name, staff_name, type, reason, duration_ms,
				   created_at, expires_at, active, offence, case_id, points)
				VALUES (?,?,?,?,?,?,?,?,1,?,?,?)
				""";
		long now = System.currentTimeMillis();
		try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, target.toString());
			ps.setString(2, targetName);
			ps.setString(3, staffName);
			ps.setString(4, type.name());
			ps.setString(5, reason);
			if (expiresAt == null) ps.setNull(6, java.sql.Types.INTEGER);
			else ps.setLong(6, expiresAt - now);
			ps.setLong(7, now);
			if (expiresAt == null) ps.setNull(8, java.sql.Types.INTEGER);
			else ps.setLong(8, expiresAt);
			ps.setString(9, offenceId);
			ps.setString(10, caseId);
			// Only warnings carry points. A ban is not three warnings, and counting it as
			// such would let the ladder escalate off the back of its own escalation.
			ps.setInt(11, type == PunishmentType.WARN ? WarningPoints.defaultPoints() : 0);
			ps.executeUpdate();

			try (ResultSet keys = ps.getGeneratedKeys()) {
				long id = keys.next() ? keys.getLong(1) : -1;
				return new Punishment(id, target, targetName, staffName, type, reason, now,
						expiresAt, true, null, caseId, null, null);
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] record failed", e);
			return null;
		}
	}

	public void clearHistory(UUID target) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement("DELETE FROM punishments WHERE target_uuid=?")) {
			ps.setString(1, target.toString());
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] clear failed", e);
		}
	}

	public void deactivate(long id) {
		Connection c = conn();
		if (c == null) return;
		try (PreparedStatement ps = c.prepareStatement("UPDATE punishments SET active=0 WHERE id=?")) {
			ps.setLong(1, id);
			ps.executeUpdate();
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Punish] deactivate failed", e);
		}
	}

	// --------------------------------------------------------------------- plumbing

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}

	private static Long revokedAt(ResultSet rs) throws SQLException {
		long at = rs.getLong("revoked_at");
		return rs.wasNull() ? null : at;
	}

	/** The punishments a revoke is about to lift, read before it lifts them. */
	private List<Punishment> activeOfTypes(UUID target, String types) {
		List<Punishment> out = new java.util.ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(
				"SELECT * FROM punishments WHERE target_uuid=? AND active=1 AND type IN " + types)) {
			ps.setString(1, target.toString());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(map(rs));
			}
		} catch (SQLException e) {
			// Losing the case note is a smaller failure than refusing the reversal.
			StaffCore.LOGGER.warn("[Punish] could not read what is being revoked: {}", e.getMessage());
		}
		return out;
	}

	private Punishment map(ResultSet rs) throws SQLException {
		long exp = rs.getLong("expires_at");
		Long expires = rs.wasNull() ? null : exp;
		return new Punishment(
				rs.getLong("id"),
				UUID.fromString(rs.getString("target_uuid")),
				rs.getString("target_name"),
				rs.getString("staff_name"),
				PunishmentType.valueOf(rs.getString("type")),
				rs.getString("reason"),
				rs.getLong("created_at"),
				expires,
				rs.getInt("active") == 1,
				rs.getString("revoked_by"),
				rs.getString("case_id"),
				revokedAt(rs),
				rs.getString("revoke_reason"));
	}
}
