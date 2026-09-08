package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.util.TimeFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Who else was online when something was recorded.
 * <p>
 * An appeal is an argument about a moment nobody wrote down. The block log says what changed
 * and the audit row says who did what about it, but the question that actually decides a
 * contested appeal — was there anybody who could say what happened — has no record at all, and
 * cannot be reconstructed afterwards. By the time anybody thinks to ask, nobody remembers
 * which evening it was.
 * <p>
 * "Nobody else was on" and "eleven people were watching" are different cases and lead to
 * different decisions. A list of names costs a few hundred bytes per incident.
 *
 * <h2>What it is not</h2>
 * Not a claim that these people saw anything. They were connected, which is a much weaker
 * statement than present, and someone alone in the nether is a witness to nothing. It is a
 * list of people worth asking, and the output says so — a staff member reading this as proof
 * of anything is reading it wrong, and the wording is the only defence against that.
 */
public final class Witnesses {
	private Witnesses() {}

	/** What an incident is attached to. */
	public enum Kind {
		PUNISHMENT("punishment"),
		REPORT("report"),
		SIGNAL("signal");

		private final String stored;

		Kind(String stored) {
			this.stored = stored;
		}

		public String stored() {
			return stored;
		}
	}

	/** Who was connected, and when. */
	public record Record(long at, String world, List<String> names, int present) {

		/** True when there was nobody else on — which is itself a finding. */
		public boolean alone() {
			return names.isEmpty();
		}
	}

	/**
	 * Records the online list against an incident.
	 * <p>
	 * The subject is excluded, because "the person being punished was online" is not
	 * information. Staff are not excluded: a second staff member who was on at the time is
	 * frequently the most useful person to ask, and leaving them out to keep the list short
	 * would remove the best entry in it.
	 *
	 * @param subject the player the incident is about, left out of the list
	 */
	public static void record(MinecraftServer server, Kind kind, String ref, String subject) {
		if (server == null || ref == null || !StaffCore.storage().isReady()) return;

		List<String> names = new ArrayList<>();
		String world = null;

		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			String name = Mc.name(online);
			if (name == null || name.equalsIgnoreCase(subject)) {
				if (name != null) world = Mc.dimensionId(online.level());
				continue;
			}
			names.add(name);
		}

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement("""
				INSERT INTO incident_witness (kind, ref, at, world, names, present)
				VALUES (?,?,?,?,?,?)
				""")) {
			ps.setString(1, kind.stored());
			ps.setString(2, ref);
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, world);
			ps.setString(5, String.join(",", names));
			ps.setInt(6, names.size());
			ps.executeUpdate();
		} catch (SQLException e) {
			// Losing the witness list should never cost the punishment it belongs to. It is
			// context, and the record it hangs off is already written by the time we get here.
			StaffCore.LOGGER.warn("[Witness] could not record who was online: {}", e.getMessage());
		}
	}

	/** Who was on when this incident was recorded, or null when nothing was written. */
	public static Record forIncident(Kind kind, String ref) {
		if (ref == null || !StaffCore.storage().isReady()) return null;

		try (PreparedStatement ps = StaffCore.storage().conn().prepareStatement(
				"SELECT at, world, names, present FROM incident_witness "
						+ "WHERE kind = ? AND ref = ? ORDER BY at DESC LIMIT 1")) {
			ps.setString(1, kind.stored());
			ps.setString(2, ref);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;

				String names = rs.getString("names");
				return new Record(rs.getLong("at"), rs.getString("world"),
						names == null || names.isBlank() ? List.of()
								: List.of(names.split(",")),
						rs.getInt("present"));
			}
		} catch (SQLException e) {
			StaffCore.LOGGER.warn("[Witness] could not read a witness list: {}", e.getMessage());
			return null;
		}
	}

	/** One line for a case view or an appeal, worded so it cannot be read as proof. */
	public static String describe(Record record) {
		if (record == null) return "Nobody recorded who was online.";
		if (record.alone()) {
			return "Nobody else was connected at " + TimeFormat.full(record.at())
					+ ", so there is nobody to ask.";
		}
		return record.present() + " other player(s) were connected at "
				+ TimeFormat.full(record.at()) + ": " + String.join(", ", record.names())
				+ ". Connected, not necessarily watching.";
	}
}
