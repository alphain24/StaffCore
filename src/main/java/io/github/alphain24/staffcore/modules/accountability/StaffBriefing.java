package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.gui.Icon;
import io.github.alphain24.staffcore.gui.Link;
import io.github.alphain24.staffcore.gui.Theme;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.cases.Case;
import io.github.alphain24.staffcore.permission.Nodes;
import io.github.alphain24.staffcore.permission.Permissions;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The three things a staff member needs to know the moment they log in.
 * <p>
 * A shift starts by finding out what happened while you were away, and the usual way to do
 * that is to open four screens. The information is small — three numbers — and putting it in
 * front of somebody costs nothing and removes the step where they do not bother.
 *
 * <h2>Three lines, once, and only when there is something to say</h2>
 * The count is the whole design. A briefing that also mentions the weather is one people
 * scroll past, and a line saying "0 reports waiting" trains everybody to ignore the line that
 * says four. So each line appears only when its number is non-zero, and there are never more
 * than three of them.
 * <p>
 * Each is a link, because the next thing anybody does after reading a count is open the thing
 * it counted.
 */
public final class StaffBriefing {
	private StaffBriefing() {}

	/** What is waiting, as plain numbers. Separated from the rendering so it can be tested. */
	public record Standing(int openReports, int openCases, int expiringToday) {

		public boolean isQuiet() {
			return openReports == 0 && openCases == 0 && expiringToday == 0;
		}
	}

	/**
	 * What is waiting right now.
	 * <p>
	 * Counts only, and cheap ones. This runs on the join path, which is the worst place in the
	 * server to do real work — a slow query here is felt by every player logging in, staff or
	 * not, and the thing it would be delaying is a line of text.
	 */
	public static Standing standing() {
		return new Standing(Mods.reports().openCount(),
				Mods.cases().store().count(Case.Status.OPEN)
						+ Mods.cases().store().count(Case.Status.INVESTIGATING),
				expiringToday());
	}

	/**
	 * Punishments running out in the next twenty-four hours.
	 * <p>
	 * Worth knowing in advance rather than afterwards: a ban ending today is the last chance
	 * to decide it should not, and finding out a week later that somebody walked back in is
	 * the same information arriving too late to be a decision.
	 */
	static int expiringToday() {
		if (!StaffCore.storage().isReady()) return 0;

		long now = System.currentTimeMillis();
		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT COUNT(*) FROM punishments WHERE active = 1 AND expires_at IS NOT NULL "
						+ "AND expires_at > ? AND expires_at <= ?")) {
			ps.setLong(1, now);
			ps.setLong(2, now + 86_400_000L);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	/**
	 * The lines to print, in order, or empty when there is nothing worth saying.
	 * <p>
	 * Permissions are checked per line rather than for the briefing as a whole. A helper who
	 * cannot see cases should not be told how many are open — it is a number they can do
	 * nothing with, and it invites them to ask somebody who can.
	 */
	public static List<MutableComponent> linesFor(ServerPlayer staff, Standing standing) {
		List<MutableComponent> out = new ArrayList<>();
		if (standing.isQuiet()) return out;

		if (standing.openReports() > 0 && Permissions.check(staff, Nodes.REPORT_VIEW)) {
			out.add(line(standing.openReports() + " report(s) waiting", "/staff reports",
					"Open the report queue"));
		}
		if (standing.openCases() > 0 && Permissions.check(staff, Nodes.STAFF_GUI)) {
			out.add(line(standing.openCases() + " case(s) still open", "/staff cases",
					"Open the case list"));
		}
		if (standing.expiringToday() > 0 && Permissions.check(staff, Nodes.HISTORY)) {
			out.add(line(standing.expiringToday() + " punishment(s) end today", "/staff stats",
					"A ban ending today is the last chance to decide it should not"));
		}
		return out;
	}

	private static MutableComponent line(String text, String command, String hover) {
		return Theme.prefix()
				.append(Icon.text("  ", Theme.MUTED))
				.append(Link.run(text, command, Theme.WARN, hover));
	}
}
