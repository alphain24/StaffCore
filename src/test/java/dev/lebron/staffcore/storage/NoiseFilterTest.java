package dev.lebron.staffcore.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grief log's "hide mining noise" filter, at the SQL level.
 * <p>
 * This exists because of a bug that made the whole screen useless on a default install and
 * announced itself as nothing at all. The clause that excludes stone and dirt was built by a
 * method nobody called, while the parameters for it were still being bound — so the statement
 * had thirty values too many for its placeholders, threw, got caught, and the screen drew an
 * empty page. An empty grief log and a quiet area look identical.
 * <p>
 * {@code hideMiningNoise} defaults to true, so this was the default path: every server would
 * have opened the grief log and been told nothing had happened.
 * <p>
 * The real query lives behind a module that needs a running server, so this reconstructs the
 * same statement shape against a real database. What it pins is the thing that actually broke:
 * that the clause and its bindings agree.
 */
class NoiseFilterTest {

	@TempDir
	Path world;

	private Storage storage;

	private Storage open() {
		storage = new Storage();
		storage.open(world);
		return storage;
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private static final List<String> NOISE = List.of("minecraft:stone", "minecraft:dirt");

	/** The clause the module builds, kept in step with {@code GriefModule#noiseClause}. */
	private static String noiseClause(boolean hide) {
		if (!hide) return "";
		StringBuilder sb = new StringBuilder(" AND NOT (action = 'BREAK' AND block IN (");
		for (int i = 0; i < NOISE.size(); i++) sb.append(i == 0 ? "?" : ",?");
		return sb.append("))").toString();
	}

	private void seed() throws SQLException {
		try (Statement st = storage.conn().createStatement()) {
			st.executeUpdate("INSERT INTO block_log (player_name, action, block, world, x, y, z, created_at) "
					+ "VALUES ('Alt','BREAK','minecraft:chest','minecraft:overworld',0,64,0,1000)");
			st.executeUpdate("INSERT INTO block_log (player_name, action, block, world, x, y, z, created_at) "
					+ "VALUES ('Alt','BREAK','minecraft:stone','minecraft:overworld',1,64,0,1001)");
			st.executeUpdate("INSERT INTO block_log (player_name, action, block, world, x, y, z, created_at) "
					+ "VALUES ('Alt','BREAK','minecraft:dirt','minecraft:overworld',2,64,0,1002)");
		}
	}

	private List<String> query(boolean hideNoise, boolean bindNoise) throws SQLException {
		String sql = "SELECT block FROM block_log"
				+ " WHERE world = ? AND created_at >= ? AND rolled_back = 0"
				+ " AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?"
				+ noiseClause(hideNoise)
				+ " ORDER BY created_at DESC LIMIT ? OFFSET ?";

		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = storage.conn().prepareStatement(sql)) {
			int i = 1;
			ps.setString(i++, "minecraft:overworld");
			ps.setLong(i++, 0);
			ps.setInt(i++, -50);
			ps.setInt(i++, 50);
			ps.setInt(i++, 0);
			ps.setInt(i++, 128);
			ps.setInt(i++, -50);
			ps.setInt(i++, 50);
			if (bindNoise) {
				for (String id : NOISE) ps.setString(i++, id);
			}
			ps.setInt(i++, 20);
			ps.setInt(i, 0);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString("block"));
			}
		}
		return out;
	}

	@Test
	@DisplayName("with the filter off, everything the alt broke is listed")
	void filterOff() throws SQLException {
		open();
		seed();

		List<String> blocks = query(false, false);
		assertEquals(3, blocks.size());
		assertTrue(blocks.contains("minecraft:chest"), "the chest break must be there");
	}

	@Test
	@DisplayName("with the filter on, stone and dirt drop out and the chest stays")
	void filterOn() throws SQLException {
		open();
		seed();

		List<String> blocks = query(true, true);
		assertEquals(List.of("minecraft:chest"), blocks,
				"hiding mining noise must hide the noise, not the evidence");
	}

	@Test
	@DisplayName("binding the noise values without the clause is an error, not an empty result")
	void bindingWithoutTheClauseThrows() throws SQLException {
		open();
		seed();

		// Exactly the shape of the bug: parameters bound for a clause the SQL does not have.
		//
		// The type matters more than it looks. This is an ArrayIndexOutOfBoundsException, not
		// a SQLException — so the module's catch, which only caught SQLException, never saw
		// it. The failure escaped into a CompletableFuture nobody was observing, the callback
		// that would have redrawn the screen never ran, and the grief log sat on "Reading the
		// log…" with nothing logged anywhere. Pinning the type is pinning why it was invisible.
		assertThrows(RuntimeException.class, () -> query(false, true));
	}
}
