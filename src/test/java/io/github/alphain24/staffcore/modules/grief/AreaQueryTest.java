package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.storage.Storage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grief log's area query — the real one, not a copy of it.
 * <p>
 * An earlier test for this reconstructed the statement by hand, which meant it proved that
 * the copy was consistent with itself and said nothing at all about the code that ships.
 * This calls {@link GriefModule#areaFilter} directly and runs what it produces against a real
 * database.
 * <p>
 * What broke here was not a typo. The clause excluding mining noise was built by a method
 * nobody called, while its parameters were still being bound — so the statement had more
 * values than placeholders. That throws {@code ArrayIndexOutOfBoundsException}, not
 * {@code SQLException}, so it slipped past the catch, escaped into a future nobody was
 * observing, and left the screen loading forever with nothing logged. Since
 * {@code hideMiningNoise} defaults to true, that was every server's default path.
 * <p>
 * The invariant worth holding is boring and easy to break again: <b>the number of {@code ?}
 * in the filter equals the number of values bound into it.</b>
 */
class AreaQueryTest {

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

	private static int placeholders(String sql) {
		return (int) sql.chars().filter(c -> c == '?').count();
	}

	// ------------------------------------------------------- the invariant itself

	@Test
	@DisplayName("every filter binds exactly as many values as it has placeholders")
	void placeholdersMatchBindings() {
		for (boolean hasPlayer : new boolean[] { false, true }) {
			for (boolean hideNoise : new boolean[] { false, true }) {
				for (String action : new String[] { "", "  AND action = 'BREAK'\n" }) {
					String sql = GriefModule.areaFilter(action, hasPlayer, hideNoise);

					assertEquals(GriefModule.areaFilterParameters(hasPlayer, hideNoise),
							placeholders(sql),
							"player=" + hasPlayer + " noise=" + hideNoise
									+ " — placeholders and bindings must agree, or the statement "
									+ "throws an index error that never reaches a SQLException catch");
				}
			}
		}
	}

	@Test
	@DisplayName("hiding noise actually puts the clause in the statement")
	void noiseClauseIsPresent() {
		String off = GriefModule.areaFilter("", false, false);
		String on = GriefModule.areaFilter("", false, true);

		assertTrue(!off.contains("block IN"), "with the filter off there is nothing to exclude");
		assertTrue(on.contains("action = 'BREAK' AND block IN"),
				"with the filter on the clause has to be in the SQL, not merely built somewhere");
		assertTrue(placeholders(on) > placeholders(off), "and it brings placeholders with it");
	}

	// ------------------------------------------- the real statement, really executed

	private void seed() throws SQLException {
		try (Statement st = storage.conn().createStatement()) {
			for (String[] row : new String[][] {
					{ "Alt", "BREAK", "minecraft:chest", "0" },
					{ "Alt", "BREAK", "minecraft:stone", "1" },
					{ "Alt", "BREAK", "minecraft:dirt", "2" },
					{ "Owner", "BREAK", "minecraft:diamond_ore", "3" },
			}) {
				st.executeUpdate("INSERT INTO block_log "
						+ "(player_name, action, block, world, x, y, z, created_at) VALUES ('"
						+ row[0] + "','" + row[1] + "','" + row[2]
						+ "','minecraft:overworld'," + row[3] + ",64,0,1000)");
			}
		}
	}

	/** Runs the shipped filter with the same binding order {@code queryPage} uses. */
	private List<String> run(String player, boolean hideNoise) throws SQLException {
		String filter = GriefModule.areaFilter("", player != null, hideNoise);
		String sql = "SELECT * " + filter + "ORDER BY created_at DESC LIMIT ? OFFSET ?";

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
			if (player != null) ps.setString(i++, player);

			i = GriefModule.bindNoise(ps, i, hideNoise);
			ps.setInt(i++, 50);
			ps.setInt(i, 0);

			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString("block"));
			}
		}
		return out;
	}

	@Test
	@DisplayName("with the noise filter on, the chest survives and the stone does not")
	void realQueryWithNoiseHidden() throws SQLException {
		open();
		seed();

		// The defaults have to contain these for the test to mean anything.
		List<String> noise = StaffConfig.get().miningNoise;
		assertTrue(noise.contains("minecraft:stone") && noise.contains("minecraft:dirt"),
				"this test assumes stone and dirt are configured as noise");

		List<String> blocks = run(null, true);

		assertTrue(blocks.contains("minecraft:chest"),
				"a broken chest is the whole point of the screen and must never be filtered out");
		assertTrue(blocks.contains("minecraft:diamond_ore"), "nor should ore be");
		assertTrue(!blocks.contains("minecraft:stone"), "stone is noise");
		assertTrue(!blocks.contains("minecraft:dirt"), "so is dirt");
	}

	@Test
	@DisplayName("with the filter off, nothing is hidden")
	void realQueryWithNoiseShown() throws SQLException {
		open();
		seed();

		assertEquals(4, run(null, false).size());
	}

	@Test
	@DisplayName("filtering to one player still applies the noise filter correctly")
	void realQueryWithPlayerAndNoise() throws SQLException {
		open();
		seed();

		// Both optional clauses at once — the combination where a binding order mistake
		// shows up, because the player parameter sits between the ranges and the noise list.
		List<String> blocks = run("Alt", true);

		assertEquals(List.of("minecraft:chest"), blocks,
				"the alt broke a chest, some stone and some dirt; only the chest is evidence");
	}
}
