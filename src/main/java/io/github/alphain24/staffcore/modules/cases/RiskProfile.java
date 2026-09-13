package io.github.alphain24.staffcore.modules.cases;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.identity.IdentityModule;
import io.github.alphain24.staffcore.modules.punish.Punishment;
import io.github.alphain24.staffcore.modules.punish.PunishmentType;
import net.minecraft.server.MinecraftServer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything already on record about one player, weighed and added up — with every point shown.
 *
 * <h2>What this is for</h2>
 * A staff member opening somebody's file has to piece together whether they are looking at a
 * first-time visitor or somebody with three bans, an open griefing case and a banned alt. All of
 * that is already recorded, on six different screens. This puts it in one place, orders it, and
 * gives it a rough size.
 *
 * <h2>What it is not</h2>
 * A verdict, a prediction, or an input to anything. Nothing reads the score to decide anything:
 * no alert fires on it, no case opens on it, nobody is punished or refused because of it. The
 * number exists only so the factors can be sorted and so "high" means the same thing on every
 * file — and it is never shown without the factors that make it up, because a bare number about
 * a person invites being acted on without being read.
 * <p>
 * The weights are judgement, written down in one place ({@link #assess}) so they can be argued
 * with. Lifted punishments and cleared cases count for nothing: a punishment that was reversed or
 * a suspicion that was investigated and dropped is not evidence of anything.
 */
public final class RiskProfile {
	private RiskProfile() {}

	public enum Level {
		LOW("Low"), ELEVATED("Elevated"), HIGH("High");

		private final String label;

		Level(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** Where a factor's evidence can be read. */
	public enum Source { PUNISHMENTS, CASES, SIGNALS, REPORTS, ACCOUNTS, MINING }

	/** One reason, how much it adds, and where to read the evidence. */
	public record Factor(String label, int points, String detail, Source source) {}

	public record Assessment(int score, Level level, List<Factor> factors) {}

	/** The facts, gathered once; {@link #assess} is a pure function of them. */
	public record Facts(boolean banned, boolean muted, int recentBans, int recentMutesAndKicks,
			int recentWarnings, int openCases, int openSevereCases, int actionedCases,
			Map<Signal.Type, Integer> strongestSignal, int reporters, int bannedExactAlts,
			int bannedRangeAlts, long accountAgeMs, int xrayScore) {}

	public static final long DAY = 86_400_000L;

	/** The weighing. Each rule is one line of arithmetic and one sentence a staff member reads. */
	public static Assessment assess(Facts f) {
		List<Factor> out = new ArrayList<>();

		if (f.banned()) out.add(new Factor("Banned right now", 40,
				"an account ban is in force", Source.PUNISHMENTS));
		if (f.muted()) out.add(new Factor("Muted right now", 10,
				"a mute is in force", Source.PUNISHMENTS));
		if (f.recentBans() > 0) out.add(new Factor("Earlier bans", Math.min(30, 15 * f.recentBans()),
				f.recentBans() + " in the last 180 days, not lifted", Source.PUNISHMENTS));
		if (f.recentMutesAndKicks() > 0) out.add(new Factor("Mutes and kicks",
				Math.min(15, 5 * f.recentMutesAndKicks()),
				f.recentMutesAndKicks() + " in the last 90 days, not lifted", Source.PUNISHMENTS));
		if (f.recentWarnings() > 0) out.add(new Factor("Warnings", Math.min(8, 2 * f.recentWarnings()),
				f.recentWarnings() + " in the last 90 days", Source.PUNISHMENTS));

		if (f.openCases() > 0) out.add(new Factor("Open cases",
				Math.min(40, 10 * f.openCases() + 10 * f.openSevereCases()),
				f.openCases() + " open" + (f.openSevereCases() == 0 ? ""
						: ", " + f.openSevereCases() + " of them severity 70 or more"), Source.CASES));
		if (f.actionedCases() > 0) out.add(new Factor("Cases that ended in action",
				Math.min(20, 10 * f.actionedCases()),
				f.actionedCases() + " in the last 180 days", Source.CASES));

		int signalPoints = 0;
		List<String> kinds = new ArrayList<>();
		for (var entry : f.strongestSignal().entrySet()) {
			signalPoints += entry.getValue() / 10;
			kinds.add(entry.getKey().label() + " " + entry.getValue());
		}
		if (signalPoints > 0) out.add(new Factor("Detector signals", Math.min(30, signalPoints),
				"strongest in the last 30 days: " + String.join(", ", kinds), Source.SIGNALS));

		if (f.reporters() > 0) out.add(new Factor("Reported by players", Math.min(16, 4 * f.reporters()),
				f.reporters() + " different player(s) in the last 30 days", Source.REPORTS));

		if (f.bannedExactAlts() > 0) out.add(new Factor("Banned account on the same address", 25,
				f.bannedExactAlts() + " — a household shares addresses too", Source.ACCOUNTS));
		else if (f.bannedRangeAlts() > 0) out.add(new Factor("Banned account on a nearby address", 8,
				f.bannedRangeAlts() + " — weak: the same provider's block", Source.ACCOUNTS));

		if (f.accountAgeMs() >= 0 && f.accountAgeMs() < DAY) {
			out.add(new Factor("New here", 5, "first joined less than a day ago", Source.ACCOUNTS));
		} else if (f.accountAgeMs() >= 0 && f.accountAgeMs() < 7 * DAY) {
			out.add(new Factor("Fairly new", 2, "first joined less than a week ago", Source.ACCOUNTS));
		}

		if (f.xrayScore() >= 40) out.add(new Factor("Mining this session", f.xrayScore() / 5,
				"live x-ray score " + f.xrayScore() + " of 99", Source.MINING));

		out.sort(Comparator.comparingInt(Factor::points).reversed());
		int score = Math.min(100, out.stream().mapToInt(Factor::points).sum());
		Level level = score >= 50 ? Level.HIGH : score >= 20 ? Level.ELEVATED : Level.LOW;
		return new Assessment(score, level, out);
	}

	// ------------------------------------------------------------------ gathering

	/**
	 * Reads the facts for one player. Server thread, when a staff member opens the profile: a
	 * handful of indexed reads, the same ones the player file already makes to draw itself.
	 */
	public static Facts gather(MinecraftServer server, UUID player) {
		long now = System.currentTimeMillis();

		int recentBans = 0, recentMutesAndKicks = 0, recentWarnings = 0;
		for (Punishment p : Mods.punish().history(player)) {
			if (p.revokedBy() != null) continue;
			long age = now - p.createdAt();
			if (p.type().isBan() && !p.inForce() && age < 180 * DAY) recentBans++;
			else if ((p.type().isMute() && !p.inForce() || p.type() == PunishmentType.KICK)
					&& age < 90 * DAY) recentMutesAndKicks++;
			else if (p.type() == PunishmentType.WARN && age < 90 * DAY) recentWarnings++;
		}

		int open = 0, severe = 0, actioned = 0;
		for (Case c : Mods.cases().store().historyFor(player, 200)) {
			if (c.status().isLive()) {
				open++;
				if (c.severity() >= 70) severe++;
			} else if (c.status() == Case.Status.ACTIONED && c.closedAt() != null
					&& now - c.closedAt() < 180 * DAY) {
				actioned++;
			}
		}

		int exact = 0, range = 0;
		for (IdentityModule.Alt alt : Mods.identity().altsOf(player)) {
			if (!alt.banned()) continue;
			if (alt.isStrong()) exact++;
			else range++;
		}

		var session = Mods.security().oreSense().sessionFor(player);
		int xray = session == null || now - session.lastAt() > 60 * 60_000L ? 0 : session.confidence();

		return new Facts(Mods.punish().activeBan(player) != null,
				Mods.punish().activeMute(player) != null, recentBans, recentMutesAndKicks,
				recentWarnings, open, severe, actioned, strongestSignals(player, now - 30 * DAY),
				reporters(player, now - 30 * DAY), exact, range, accountAge(player, now), xray);
	}

	private static Map<Signal.Type, Integer> strongestSignals(UUID player, long since) {
		Map<Signal.Type, Integer> out = new EnumMap<>(Signal.Type.class);
		if (!StaffCore.storage().isReady()) return out;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT type, MAX(confidence) FROM signals
				WHERE subject_uuid = ? AND occurred_at >= ?
				GROUP BY type
				""")) {
			ps.setString(1, player.toString());
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Signal.Type type = Signal.Type.of(rs.getString(1));
					// A report is counted by who made it, below, not by its confidence.
					if (type != Signal.Type.REPORT) out.merge(type, rs.getInt(2), Math::max);
				}
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Risk] could not read signals: {}", e.getMessage());
		}
		return out;
	}

	private static int reporters(UUID player, long since) {
		if (!StaffCore.storage().isReady()) return 0;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				SELECT COUNT(DISTINCT COALESCE(reporter_uuid, reporter_name)) FROM reports
				WHERE target_uuid = ? AND created_at >= ?
				""")) {
			ps.setString(1, player.toString());
			ps.setLong(2, since);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/** How long since this account first joined, or -1 when it never has. */
	private static long accountAge(UUID player, long now) {
		if (!StaffCore.storage().isReady()) return -1;
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT MIN(first_seen) FROM connections WHERE uuid = ?")) {
			ps.setString(1, player.toString());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return -1;
				long first = rs.getLong(1);
				return rs.wasNull() ? -1 : now - first;
			}
		} catch (SQLException e) {
			return -1;
		}
	}
}
