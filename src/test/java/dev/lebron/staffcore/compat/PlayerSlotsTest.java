package dev.lebron.staffcore.compat;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot count a player inventory actually has.
 * <p>
 * This exists because of a bug that was invisible in every obvious way. Snapshots captured
 * armour and offhand correctly and wrote them to the database; the reader then allocated its
 * buffer as {@code new ItemStack[Inventory.INVENTORY_SIZE]} and dropped every row past slot
 * 35 on a bounds check. Nothing threw, nothing logged, the rows were all still there — the
 * armour slots simply came back empty, and restoring a death snapshot left the player with
 * no armour and nothing in their offhand.
 * <p>
 * The trap is that {@code INVENTORY_SIZE} reads like "the size of the inventory" and is not:
 * it is storage plus hotbar only. These assertions pin the difference, so a future version
 * that moves equipment around fails here rather than in front of somebody who has just lost
 * their netherite.
 */
class PlayerSlotsTest {

	@Test
	@DisplayName("INVENTORY_SIZE is not the whole inventory")
	void inventorySizeIsNotTheWholeInventory() {
		assertEquals(36, Inventory.INVENTORY_SIZE,
				"storage plus hotbar only — armour and offhand live past this");
		assertTrue(Mc.PLAYER_SLOTS > Inventory.INVENTORY_SIZE,
				"a buffer sized by INVENTORY_SIZE silently truncates equipment");
	}

	@Test
	@DisplayName("every equipment slot is addressable and past the storage array")
	void equipmentSlotsAreAddressable() {
		assertEquals(43, Mc.PLAYER_SLOTS, "36 storage slots plus 7 equipment slots in 26.2");

		for (var mapped : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
			int slot = mapped.getIntKey();
			assertTrue(slot >= Inventory.INVENTORY_SIZE,
					"equipment slot " + slot + " should sit past the storage array");
			assertTrue(slot < Mc.PLAYER_SLOTS,
					"equipment slot " + slot + " must fit inside a PLAYER_SLOTS buffer");
			assertNotNull(mapped.getValue());
		}
	}

	@Test
	@DisplayName("armour and offhand sit where the inventory screens expect them")
	void armourAndOffhandIndices() {
		// The invsee and snapshot screens both map GUI slots 36-40 straight onto these, so
		// a reshuffle upstream would silently show the wrong item in the wrong box.
		assertEquals(EquipmentSlot.FEET, Inventory.EQUIPMENT_SLOT_MAPPING.get(36));
		assertEquals(EquipmentSlot.LEGS, Inventory.EQUIPMENT_SLOT_MAPPING.get(37));
		assertEquals(EquipmentSlot.CHEST, Inventory.EQUIPMENT_SLOT_MAPPING.get(38));
		assertEquals(EquipmentSlot.HEAD, Inventory.EQUIPMENT_SLOT_MAPPING.get(39));
		assertEquals(EquipmentSlot.OFFHAND, Inventory.EQUIPMENT_SLOT_MAPPING.get(Inventory.SLOT_OFFHAND));
		assertEquals(40, Inventory.SLOT_OFFHAND);
	}
}
