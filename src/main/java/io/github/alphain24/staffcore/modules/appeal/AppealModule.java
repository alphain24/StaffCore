package io.github.alphain24.staffcore.modules.appeal;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.api.StaffCoreApi;
import io.github.alphain24.staffcore.api.StaffCoreEvent;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Module;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Somewhere for a punished player to argue their case.
 * <p>
 * Without this the Discord bridge is one-way: staff can announce a ban but the player has
 * nowhere to answer, so the only move left to them is an alt. An appeal turns that into a
 * conversation with a record attached — and a rejected appeal is itself useful evidence
 * the next time the same person turns up.
 * <p>
 * Muted players can still appeal. A mute stops them talking in chat; it is not meant to
 * stop them contesting the mute.
 *
 * <h2>One door each way</h2>
 * An appeal is filed through {@link #fileAgainst} and decided through {@link #decide}, whether it
 * came from {@code /appeal} in game, a code typed into Discord, the appeals screen or a button. The
 * rules live there and nowhere else: one open appeal per punishment, a wait after a rejection, and
 * an accepted appeal lifting the punishment it was about.
 * <p>
 * A decision also ends the appeal code it was filed with ({@link AppealCodes}). A rejection issues the
 * punishment a new code that works from the day the deciding staff member chose, and that is the code
 * the ban screen shows from then on.
 *
 * <h2>States, never deletions</h2>
 * {@code OPEN}, then {@code CLOSED} with a verdict of {@code ACCEPTED}, {@code REJECTED} or
 * {@code CLOSED}, or {@code STALE} when the player stopped answering. Every row stays.
 */
public class AppealModule implements Module {

	@Override
	public String id() {
		return "appeal";
	}

	@Override
	public String displayName() {
		return "Appeals";
	}

	/**
	 * One appeal.
	 *
	 * @param punishmentId    the punishment it is against; null only for rows older than the column
	 * @param source          {@code GAME} or {@code DISCORD}; null for rows older than the column
	 * @param discordId       the Discord account that filed it, when it came from Discord
	 * @param infoRequestedAt when staff last asked the player something, or null
	 * @param repliedAt       when the player last answered, or null
	 */
	public record Appeal(long id, UUID targetUuid, String targetName, String text,
			String status, String handledBy, String verdict, long createdAt, Long handledAt,
			Long punishmentId, String source, String discordId, String discordName,
			Long infoRequestedAt, Long repliedAt) {

		public boolean isOpen() {
			return "OPEN".equals(status);
		}

		/** Staff have asked a question the player has not answered since. */
		public boolean waitingOnPlayer() {
			return isOpen() && infoRequestedAt != null && (repliedAt == null || repliedAt < infoRequestedAt);
		}
	}

	public enum Result { OK, ALREADY_OPEN, UNAVAILABLE, NOTHING_TO_APPEAL, NOT_IN_FORCE, COOLDOWN, NO_CODE }

	/**
	 * What filing did.
	 *
	 * @param appeal         the appeal filed, or the one already open when that is the answer
	 * @param mayAppealAgain when a rejected appeal's wait ends, for {@link Result#COOLDOWN}
	 */
	public record Filed(Result result, Appeal appeal, Long mayAppealAgain) {

		static Filed of(Result result) {
			return new Filed(result, null, null);
		}
	}

	/** A verdict staff can give. Stale is not one: nobody decides that, time does. */
	public enum Verdict { ACCEPTED, REJECTED, CLOSED }

	/** What deciding, asking or answering did, and the sentence to show. */
	public record Outcome(boolean done, String message) {

		static Outcome no(String why) {
			return new Outcome(false, why);
		}
	}

	/** The longest wait a rejection can set: a year. Longer is a punishment of its own. */
	public static final int MAX_WAIT_DAYS = 365;

	private final AppealCodes codes = new AppealCodes();
	private boolean lifecycleRegistered;

	/** Every appeal code, and whether each still works. */
	public AppealCodes codes() {
		return codes;
	}

	/**
	 * Marks appeals the player walked away from as stale: at start, and hourly after that.
	 * <p>
	 * Hourly rather than only at start, unlike cases, because a player waiting on an answer is waiting
	 * now and a server can run for weeks between restarts.
	 */
	@Override
	public void onEnable() {
		if (lifecycleRegistered) return;
		lifecycleRegistered = true;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
				server -> markStale(server, StaffConfig.get().appealStaleDays));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 72_000 == 0) markStale(server, StaffConfig.get().appealStaleDays);
		});
	}

	// -------------------------------------------------------------------- filing

	/**
	 * Files an appeal against the punishment the player is under, from the game.
	 * <p>
	 * An appeal is always an appeal <em>of something</em>. It used to be accepted from anybody,
	 * so a player with nothing against them could fill the queue staff work through, and every
	 * genuine appeal waited behind the ones that were not. In game the only thing a player can
	 * be under is a mute — somebody banned is not online to type — so a player without an active
	 * one is told there is nothing to appeal, and where a ban appeal goes instead.
	 */
	public Result file(UUID target, String targetName, String text) {
		if (conn() == null) return Result.UNAVAILABLE;
		Punishment against = Mods.punish().activeMute(target);
		if (against == null) return Result.NOTHING_TO_APPEAL;
		return fileAgainst(against, text, "GAME", null, null).result();
	}

	/**
	 * The one way an appeal is filed.
	 *
	 * @param discordId   the Discord account filing it, or null from the game
	 * @param discordName that account's name, for staff to read
	 */
	public Filed fileAgainst(Punishment against, String text, String source, String discordId,
			String discordName) {

		Connection c = conn();
		if (c == null) return Filed.of(Result.UNAVAILABLE);
		if (against == null || !against.active()) return Filed.of(Result.NOT_IN_FORCE);

		// One at a time per punishment. A second appeal while the first is open is the same request
		// twice, and splitting a conversation across two threads helps nobody.
		Appeal open = openFor(against.id());
		if (open != null) return new Filed(Result.ALREADY_OPEN, open, null);

		long now = System.currentTimeMillis();
		AppealCodes.Code current = codes.current(against.id());
		if (current != null) {
			// The wait staff set when they rejected the last appeal, carried by the code it issued.
			if (current.usableFrom() > now) return new Filed(Result.COOLDOWN, null, current.usableFrom());
		} else if (against.isAppealable() && !codesTracked(against)) {
			// A punishment whose code predates the codes table: the old rule, a fixed wait after the
			// last rejection.
			Long rejectedAt = lastRejectedAt(against.id());
			int cooldownDays = StaffConfig.get().appealCooldownDays;
			if (rejectedAt != null && cooldownDays > 0) {
				long until = rejectedAt + cooldownDays * 86_400_000L;
				if (now < until) return new Filed(Result.COOLDOWN, null, until);
			}
		} else if (against.isAppealable()) {
			// Every code this punishment had has been retired and none replaced it.
			return Filed.of(Result.NO_CODE);
		}

		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO appeals (target_uuid, target_name, text, status, created_at, punishment_id, "
						+ "source, discord_id, discord_name) VALUES (?,?,?,'OPEN',?,?,?,?,?)",
				java.sql.Statement.RETURN_GENERATED_KEYS)) {
			ps.setString(1, against.targetUuid().toString());
			ps.setString(2, against.targetName());
			ps.setString(3, text);
			ps.setLong(4, now);
			ps.setLong(5, against.id());
			ps.setString(6, source);
			ps.setString(7, discordId);
			ps.setString(8, discordName);
			ps.executeUpdate();

			long appealId = 0;
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) appealId = keys.getLong(1);
			}
			if (against.hasCase()) {
				Mods.cases().store().note(against.caseId(), against.targetName(),
						"appeal #" + appealId + " filed against " + against.type().name().toLowerCase(java.util.Locale.ROOT)
								+ " #" + against.id() + (discordName == null ? "" : " from Discord (" + discordName + ")"));
			}
			Appeal filed = byId(appealId);
			published(filed, against);
			return new Filed(Result.OK, filed, null);
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] file failed", e);
			return Filed.of(Result.UNAVAILABLE);
		}
	}

	/** Whether the player has any appeal waiting on a verdict. */
	public boolean hasOpen(UUID target) {
		return count("SELECT COUNT(*) FROM appeals WHERE target_uuid=? AND status='OPEN'", target.toString()) > 0;
	}

	// ------------------------------------------------------------------- reading

	/** Open appeals, oldest first — the same fairness rule as the report queue. */
	public List<Appeal> queue() {
		return query("SELECT * FROM appeals WHERE status='OPEN' ORDER BY created_at ASC", null);
	}

	public List<Appeal> forPlayer(UUID target) {
		return query("SELECT * FROM appeals WHERE target_uuid=? ORDER BY created_at DESC",
				target.toString());
	}

	public Appeal byId(long id) {
		List<Appeal> found = query("SELECT * FROM appeals WHERE id=?", String.valueOf(id));
		return found.isEmpty() ? null : found.get(0);
	}

	/** The open appeal against this punishment, or null. */
	public Appeal openFor(long punishmentId) {
		List<Appeal> found = query("SELECT * FROM appeals WHERE punishment_id=? AND status='OPEN' "
				+ "ORDER BY created_at DESC LIMIT 1", String.valueOf(punishmentId));
		return found.isEmpty() ? null : found.get(0);
	}

	public int openCount() {
		return count("SELECT COUNT(*) FROM appeals WHERE status='OPEN'", null);
	}

	public int openCountFor(UUID target) {
		return count("SELECT COUNT(*) FROM appeals WHERE target_uuid=? AND status='OPEN'",
				target.toString());
	}

	// ------------------------------------------------------------------ verdicts

	/** Accepts, from anywhere that has already checked who is asking. */
	public boolean accept(long id, String staffName) {
		return decide(StaffCore.server(), id, Verdict.ACCEPTED, staffName).done();
	}

	public boolean reject(long id, String staffName) {
		return decide(StaffCore.server(), id, Verdict.REJECTED, staffName).done();
	}

	/**
	 * Decides an appeal. The appeals screen and Discord both come here.
	 * <p>
	 * Accepting lifts the punishment the appeal was made against, and only that one: an appeal that
	 * is "accepted" but leaves the ban in place is worse than no appeal system at all, and one against
	 * a mute is not a reason to lift an unrelated ban. Every verdict is written into the punishment's
	 * case, when it has one, and the player is told if they are online.
	 */
	public Outcome decide(MinecraftServer server, long id, Verdict verdict, String staffName) {
		return decide(server, id, verdict, staffName, null);
	}

	/**
	 * As {@link #decide(MinecraftServer, long, Verdict, String)}, with the wait a rejection sets.
	 *
	 * @param waitDays for a rejection, how many days before the punishment can be appealed again, 0 to
	 *                 {@value #MAX_WAIT_DAYS}; null for {@code appealCooldownDays}. Ignored otherwise.
	 */
	public Outcome decide(MinecraftServer server, long id, Verdict verdict, String staffName, Integer waitDays) {
		int wait = waitDays == null ? StaffConfig.get().appealCooldownDays : waitDays;
		if (verdict == Verdict.REJECTED && (wait < 0 || wait > MAX_WAIT_DAYS)) {
			return Outcome.no("The wait has to be between 0 and " + MAX_WAIT_DAYS + " days.");
		}
		Appeal appeal = byId(id);
		if (appeal == null) return Outcome.no("There is no appeal #" + id + ".");
		if (!appeal.isOpen()) return Outcome.no("Appeal #" + id + " has already been decided.");
		if (!close(id, staffName, verdict.name())) return Outcome.no("Somebody decided appeal #" + id + " first.");

		int lifted = 0;
		String reason = "appeal #" + id + " accepted by " + staffName;
		if (verdict == Verdict.ACCEPTED) {
			if (appeal.punishmentId() != null) {
				lifted = Mods.punish().reverse(server, appeal.punishmentId(), staffName, reason);
			} else {
				// A row from before appeals recorded their punishment. The old rule is the only one
				// that can apply to it.
				lifted = Mods.punish().revoke(server, appeal.targetUuid(), staffName, true, reason)
						+ Mods.punish().revoke(server, appeal.targetUuid(), staffName, false, reason);
			}
		}

		// The code the appeal was filed with stops working either way a decision goes. A rejection
		// hands the punishment a new one, usable once the wait is over.
		long now = System.currentTimeMillis();
		Long again = null;
		if (appeal.punishmentId() != null) {
			String why = "appeal #" + id + " " + verdict.name().toLowerCase(java.util.Locale.ROOT);
			if (verdict == Verdict.ACCEPTED) {
				codes.retire(appeal.punishmentId(), staffName, why);
			} else if (verdict == Verdict.REJECTED) {
				AppealCodes.Code next = codes.rotate(appeal.punishmentId(), staffName, why, now + wait * 86_400_000L);
				if (next != null && wait > 0) again = next.usableFrom();
			}
		}

		Punishment against = appeal.punishmentId() == null ? null : Mods.punish().byId(appeal.punishmentId());
		if (against != null && against.hasCase()) {
			Mods.cases().store().note(against.caseId(), staffName, "appeal #" + id + " "
					+ verdict.name().toLowerCase(java.util.Locale.ROOT)
					+ (verdict == Verdict.REJECTED ? "; can be appealed again " + (wait == 0 ? "at once"
							: "in " + wait + " day(s)") : ""));
		}

		if (server != null) {
			ServerPlayer online = server.getPlayerList().getPlayer(appeal.targetUuid());
			if (online != null) {
				online.sendSystemMessage(switch (verdict) {
					case ACCEPTED -> Theme.good("Your appeal was accepted. Welcome back.");
					case REJECTED -> Theme.bad("Your appeal was reviewed and rejected." + (wait == 0 ? ""
							: " You can appeal again in " + wait + " day(s)."));
					case CLOSED -> Theme.warn("Your appeal was closed without a decision.");
				});
				// A muted player is the one who can read the new code; a banned one sees it on the ban screen.
				if (verdict == Verdict.REJECTED && against != null && against.active()) {
					var code = Mods.punish().appealCodeLine(against);
					if (code != null) online.sendSystemMessage(code);
				}
			}
			Mods.alerts().onStaffAction(server, "%s %s %s's appeal #%d".formatted(staffName,
					verdict.name().toLowerCase(java.util.Locale.ROOT), appeal.targetName(), id));
		}

		StaffCoreApi.publish(new StaffCoreEvent.AppealDecided(System.currentTimeMillis(), id, verdict.name(),
				staffName, appeal.discordId(), again));

		return new Outcome(true, switch (verdict) {
			case ACCEPTED -> "Appeal #" + id + " accepted. " + (lifted > 0
					? "The punishment is lifted." : "The punishment was no longer in force, so nothing was lifted.");
			case REJECTED -> "Appeal #" + id + " rejected. The punishment stands, and " + (wait == 0
					? "it can be appealed again at once with the new code on their ban screen."
					: "it can be appealed again in " + wait + " day(s), with the new code on their ban screen.");
			case CLOSED -> "Appeal #" + id + " closed without a decision.";
		});
	}

	// ------------------------------------------------------------------ the conversation

	/**
	 * Asks the player something about their appeal.
	 * <p>
	 * Only an appeal filed from Discord can be asked: that is where the answer can come back. The
	 * question starts the stale clock, which only ever runs while the player owes an answer.
	 */
	public Outcome requestInfo(long id, String staffName, String question) {
		Appeal appeal = byId(id);
		if (appeal == null) return Outcome.no("There is no appeal #" + id + ".");
		if (!appeal.isOpen()) return Outcome.no("Appeal #" + id + " has already been decided.");
		if (appeal.discordId() == null) {
			return Outcome.no("Appeal #" + id + " was filed in game, so there is no Discord account to ask. "
					+ "Ask them in game.");
		}
		if (!stamp(id, "info_requested_at")) return Outcome.no("The question could not be saved.");

		note(appeal, staffName, "asked on appeal #" + id + ": " + question);
		StaffCoreApi.publish(new StaffCoreEvent.AppealConversation(System.currentTimeMillis(), id, staffName,
				question, false, appeal.discordId()));
		return new Outcome(true, "Asked. They are sent your question and can answer by replying; the appeal "
				+ "goes stale if they have not answered in " + StaffConfig.get().appealStaleDays + " day(s).");
	}

	/**
	 * The player's answer, from the Discord account that filed the appeal.
	 * <p>
	 * Accepted only while an appeal from that account is open and staff have asked something, so a
	 * direct message to the bot is not a way to write into any appeal at will.
	 */
	public Outcome reply(String discordId, String text) {
		if (discordId == null) return Outcome.no("No Discord account.");
		List<Appeal> asked = query("SELECT * FROM appeals WHERE discord_id=? AND status='OPEN' "
				+ "AND info_requested_at IS NOT NULL ORDER BY info_requested_at DESC LIMIT 1", discordId);
		if (asked.isEmpty()) {
			return Outcome.no("You have no appeal staff are waiting to hear from you about. To appeal, use "
					+ "/appeal in the server with the code from your ban screen.");
		}
		Appeal appeal = asked.get(0);
		if (!stamp(appeal.id(), "replied_at")) return Outcome.no("Your answer could not be saved. Try again.");

		note(appeal, appeal.targetName(), "answered on appeal #" + appeal.id() + ": " + text);
		StaffCoreApi.publish(new StaffCoreEvent.AppealConversation(System.currentTimeMillis(), appeal.id(),
				appeal.targetName(), text, true, discordId));
		return new Outcome(true, "Thanks — your answer was added to appeal #" + appeal.id() + ".");
	}

	/**
	 * Closes appeals the player stopped answering.
	 * <p>
	 * An appeal is stale when staff asked it something more than {@code days} ago and the player has
	 * not answered since. Appeals nobody has asked anything are left alone however old they are:
	 * that wait is staff's, and closing it would punish the player for it.
	 *
	 * @return how many were marked
	 */
	public int markStale(MinecraftServer server, int days) {
		Connection c = conn();
		if (c == null || days <= 0) return 0;
		long now = System.currentTimeMillis();
		long cutoff = now - days * 86_400_000L;

		List<Appeal> abandoned = query("SELECT * FROM appeals WHERE status='OPEN' AND info_requested_at IS NOT NULL "
				+ "AND info_requested_at < " + cutoff + " AND (replied_at IS NULL OR replied_at < info_requested_at)",
				null);
		int marked = 0;
		for (Appeal appeal : abandoned) {
			try (PreparedStatement ps = c.prepareStatement("UPDATE appeals SET status='STALE', handled_by=?, "
					+ "handled_at=? WHERE id=? AND status='OPEN'")) {
				ps.setString(1, "StaffCore");
				ps.setLong(2, now);
				ps.setLong(3, appeal.id());
				if (ps.executeUpdate() == 0) continue;
			} catch (SQLException e) {
				StaffCore.LOGGER.warn("[Appeal] could not mark appeal {} stale: {}", appeal.id(), e.getMessage());
				continue;
			}
			marked++;
			note(appeal, "StaffCore", "appeal #" + appeal.id() + " went stale: no answer in " + days + " day(s)");
			StaffCoreApi.publish(new StaffCoreEvent.AppealDecided(now, appeal.id(), "STALE", "StaffCore",
					appeal.discordId(), null));
		}
		if (marked > 0) StaffCore.LOGGER.info("[Appeal] {} appeal(s) went stale waiting on the player", marked);
		return marked;
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * Tells companions about a new appeal, with the punishment it is against spelled out so an appeal
	 * can be read without looking anything else up.
	 */
	private static void published(Appeal appeal, Punishment against) {
		if (appeal == null || !StaffCoreApi.hasListeners()) return;
		int evidence = against.caseId() == null ? 0 : Mods.cases().evidence().forCase(against.caseId()).size();

		// Whose Discord account this is. From Discord, the account that filed; from the game, the
		// player's own link if they have one. Where the filer's account is linked to a different
		// player, staff are shown that too — somebody holding a photograph of a ban screen can file.
		String discordId = appeal.discordId();
		String linkedTo = null;
		if (discordId != null) {
			var link = Mods.discord().links().forDiscord(discordId);
			linkedTo = link == null ? null : link.playerName();
		} else {
			var link = Mods.discord().links().forPlayer(appeal.targetUuid());
			if (link != null) {
				discordId = link.discordId();
				linkedTo = link.playerName();
			}
		}

		StaffCoreApi.publish(new StaffCoreEvent.AppealFiled(appeal.createdAt(), appeal.id(), appeal.targetUuid(),
				appeal.targetName(), against.id(), against.type().name(), against.reason(), against.staffName(),
				against.createdAt(), appeal.text(), evidence, appeal.source() == null ? "GAME" : appeal.source(),
				discordId, linkedTo, against.caseId()));
	}

	/** A line in the appealed punishment's case, when it has one. */
	private static void note(Appeal appeal, String actor, String body) {
		if (appeal.punishmentId() == null) return;
		Punishment against = Mods.punish().byId(appeal.punishmentId());
		if (against != null && against.hasCase()) Mods.cases().store().note(against.caseId(), actor, body);
	}

	private boolean close(long id, String staffName, String verdict) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement(
				"UPDATE appeals SET status='CLOSED', verdict=?, handled_by=?, handled_at=? "
						+ "WHERE id=? AND status='OPEN'")) {
			ps.setString(1, verdict);
			ps.setString(2, staffName);
			ps.setLong(3, System.currentTimeMillis());
			ps.setLong(4, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] verdict failed", e);
			return false;
		}
	}

	/** Sets one of the conversation timestamps to now. The column name is ours, never input. */
	private boolean stamp(long id, String column) {
		Connection c = conn();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("UPDATE appeals SET " + column + "=? WHERE id=?")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setLong(2, id);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Appeal] could not update appeal {}: {}", id, e.getMessage());
			return false;
		}
	}

	/** Whether the codes table has ever known this punishment's code. */
	private boolean codesTracked(Punishment against) {
		AppealCodes.Code original = codes.lookup(against.appealCode());
		return original != null && !original.legacy();
	}

	private Long lastRejectedAt(long punishmentId) {
		Connection c = conn();
		if (c == null) return null;
		try (PreparedStatement ps = c.prepareStatement("SELECT MAX(handled_at) FROM appeals WHERE punishment_id=? "
				+ "AND verdict='REJECTED'")) {
			ps.setLong(1, punishmentId);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				long at = rs.getLong(1);
				return rs.wasNull() ? null : at;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	private List<Appeal> query(String sql, String arg) {
		List<Appeal> out = new ArrayList<>();
		Connection c = conn();
		if (c == null) return out;

		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (arg != null) ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new Appeal(
							rs.getLong("id"),
							UUID.fromString(rs.getString("target_uuid")),
							rs.getString("target_name"),
							rs.getString("text"),
							rs.getString("status"),
							rs.getString("handled_by"),
							rs.getString("verdict"),
							rs.getLong("created_at"),
							nullableLong(rs, "handled_at"),
							nullableLong(rs, "punishment_id"),
							rs.getString("source"),
							rs.getString("discord_id"),
							rs.getString("discord_name"),
							nullableLong(rs, "info_requested_at"),
							nullableLong(rs, "replied_at")));
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.error("[Appeal] query failed", e);
		}
		return out;
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() || value == 0 ? null : value;
	}

	private int count(String sql, String arg) {
		Connection c = conn();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			if (arg != null) ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	private static Connection conn() {
		return StaffCore.storage().isReady() ? StaffCore.storage().conn() : null;
	}
}
