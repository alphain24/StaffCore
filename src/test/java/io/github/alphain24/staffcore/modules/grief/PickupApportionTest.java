package io.github.alphain24.staffcore.modules.grief;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding who owes what when several people picked over the same pile.
 * <p>
 * This is the arithmetic that stops a restore duplicating. Restoring a death snapshot hands
 * the owner everything they had, so every copy still in the world has to be accounted for
 * exactly once: too little taken back and the items exist twice, too much and somebody is
 * charged for goods they never had.
 * <p>
 * The cap is what makes it safe to search widely. A generous radius finds more of what belongs
 * to the death, and can never take more than is missing.
 */
class PickupApportionTest {

	@TempDir
	Path world;

	private Storage storage;

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	/** The singleton, because PickupWatch reads StaffCore.storage() rather than being handed one. */
	private void open() {
		storage = StaffCore.storage();
		storage.open(world);
	}

	@AfterEach
	void close() {
		if (storage != null) storage.close();
	}

	private static final String WORLD = "minecraft:overworld";
	private static final BlockPos DEATH = new BlockPos(100, 64, 100);

	private void pickup(String who, String item, int count, int x, int z) throws SQLException {
		try (PreparedStatement ps = storage.conn().prepareStatement(
				"INSERT INTO pickup_log (uuid, player_name, item, count, world, x, y, z, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,?,?)")) {
			ps.setString(1, java.util.UUID.nameUUIDFromBytes(who.getBytes()).toString());
			ps.setString(2, who);
			ps.setString(3, item);
			ps.setInt(4, count);
			ps.setString(5, WORLD);
			ps.setInt(6, x);
			ps.setInt(7, 64);
			ps.setInt(8, z);
			ps.setLong(9, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private static Map<Item, Integer> owed(Item item, int count) {
		Map<Item, Integer> owed = new HashMap<>();
		owed.put(item, count);
		return owed;
	}

	private static int total(Map<String, Map<Item, Integer>> byPlayer, Item item) {
		return byPlayer.values().stream().mapToInt(m -> m.getOrDefault(item, 0)).sum();
	}

	@Test
	@DisplayName("nobody is charged more than went missing")
	void neverChargesMoreThanIsOwed() throws SQLException {
		open();
		// They pocketed a whole stack; only five are unaccounted for.
		pickup("Looter", "minecraft:diamond", 64, 100, 100);

		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, null,
				owed(Items.DIAMOND, 5));

		assertEquals(5, total(who, Items.DIAMOND),
				"charging for the whole stack would take items that were never the victim's");
	}

	@Test
	@DisplayName("a pile split between two people is split between them")
	void splitsAcrossPickers() throws SQLException {
		open();
		pickup("First", "minecraft:diamond", 3, 100, 100);
		pickup("Second", "minecraft:diamond", 4, 102, 101);

		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, null,
				owed(Items.DIAMOND, 7));

		assertEquals(2, who.size(), "both of them took some");
		assertEquals(7, total(who, Items.DIAMOND), "and between them they took all of it");
	}

	@Test
	@DisplayName("when more was taken than is owed, the excess is left alone")
	void stopsOnceTheDebtIsCovered() throws SQLException {
		open();
		pickup("First", "minecraft:diamond", 10, 100, 100);
		pickup("Second", "minecraft:diamond", 10, 101, 100);

		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, null,
				owed(Items.DIAMOND, 6));

		assertEquals(6, total(who, Items.DIAMOND),
				"six went missing, so six come back and no more");
	}

	@Test
	@DisplayName("the owner collecting their own drops is not charged for it")
	void theOwnerIsExcluded() throws SQLException {
		open();
		pickup("Victim", "minecraft:diamond", 5, 100, 100);
		pickup("Looter", "minecraft:diamond", 3, 101, 100);

		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, "Victim",
				owed(Items.DIAMOND, 8));

		assertTrue(!who.containsKey("Victim"), "picking up your own death drops is not theft");
		assertEquals(3, total(who, Items.DIAMOND), "only what the other one took");
	}

	@Test
	@DisplayName("items nobody picked up are nobody's debt")
	void unclaimedItemsChargeNobody() throws SQLException {
		open();
		// Burned in lava, despawned, gone. There is nothing to reclaim and nobody to bill;
		// the restore simply hands the owner a replacement, which duplicates nothing because
		// the original no longer exists.
		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, null,
				owed(Items.DIAMOND, 12));

		assertTrue(who.isEmpty(), "no pickups means no debts");
	}

	@Test
	@DisplayName("a pickup somewhere else is not this death's business")
	void staysWithinTheScene() throws SQLException {
		open();
		pickup("Elsewhere", "minecraft:diamond", 5, 900, 900);

		var who = new PickupWatch().whoTook(WORLD, DEATH, 16, 60_000L, null,
				owed(Items.DIAMOND, 5));

		assertTrue(who.isEmpty(), "someone mining diamonds a thousand blocks away owes nothing");
	}
}
