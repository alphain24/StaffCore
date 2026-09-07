package io.github.alphain24.staffcore.security;

import io.github.alphain24.staffcore.modules.security.IllegalItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contraband list, checked against the registry it is supposed to describe.
 * <p>
 * A wrong id here fails silently and looks like protection: it matches nothing, flags
 * nothing, and reads perfectly well in the config file. {@code minecraft:spawn_egg} sat in
 * the defaults doing exactly that — it is not an item, so not one of the eighty-eight actual
 * spawn eggs was ever caught by the entry meant to catch them all.
 */
class IllegalItemsTest {

	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("every default id resolves to a real item")
	void defaultsResolve() {
		List<String> names = new ArrayList<>(IllegalItems.creativeOnlyDefaults());
		names.addAll(IllegalItems.operatorOnlyDefaults());

		IllegalItems.Ruleset compiled = IllegalItems.compile(names);
		assertEquals(List.of(), compiled.unknown(),
				"a default that matches nothing is worse than no default — it reads as protection");
	}

	@Test
	@DisplayName("the spawn egg rule covers every spawn egg in the game")
	void spawnEggRuleIsComplete() {
		IllegalItems.Ruleset rules = IllegalItems.compile(IllegalItems.creativeOnlyDefaults());

		int eggs = 0;
		for (var item : BuiltInRegistries.ITEM) {
			if (!(item instanceof SpawnEggItem)) continue;
			eggs++;
			assertTrue(rules.matches(item),
					BuiltInRegistries.ITEM.getKey(item) + " should be contraband");
		}
		assertTrue(eggs > 50, "expected the game to have plenty of spawn eggs, found " + eggs);
		assertEquals(eggs, IllegalItems.spawnEggCount());
	}

	@Test
	@DisplayName("ordinary survival items are left alone")
	void survivalItemsAreNotFlagged() {
		IllegalItems.Ruleset rules = IllegalItems.compile(IllegalItems.creativeOnlyDefaults());

		// The cost of a false positive is staff confiscating something legitimate, so the
		// obvious survival staples are pinned rather than assumed.
		for (var item : List.of(Items.DIAMOND_SWORD, Items.STONE, Items.OAK_LOG, Items.CHEST,
				Items.ENDER_PEARL, Items.NETHERITE_INGOT, Items.ELYTRA, Items.TOTEM_OF_UNDYING,
				Items.SHULKER_BOX, Items.ENCHANTED_GOLDEN_APPLE, Items.DRAGON_HEAD,
				Items.WITHER_SKELETON_SKULL, Items.BEACON, Items.SPONGE)) {
			assertFalse(rules.matches(item),
					BuiltInRegistries.ITEM.getKey(item) + " is obtainable in survival");
		}
	}

	@Test
	@DisplayName("an unknown name is reported rather than silently ignored")
	void unknownNamesAreReported() {
		IllegalItems.Ruleset rules = IllegalItems.compile(
				List.of("minecraft:bedrock", "minecraft:spawn_egg", "#nonsense"));

		assertEquals(List.of("minecraft:spawn_egg", "#nonsense"), rules.unknown());
		assertTrue(rules.matches(Items.BEDROCK), "the valid entry still works");
	}
}
