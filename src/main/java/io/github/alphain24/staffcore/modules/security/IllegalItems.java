package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Items no survival player should be holding.
 * <p>
 * The list is the boring half of anti-cheat and the half that actually catches people:
 * a duped stack is arguable, a bedrock block in someone's hotbar is not. It is config
 * driven, and the defaults are split by <em>why</em> an item is impossible rather than
 * lumped together, because "creative-only" and "operator tool" want different responses —
 * the first is usually a leak from a staff member, the second is a real problem.
 * <p>
 * Entries are item ids, with one exception: a name beginning with {@code #} is a
 * <em>rule</em> rather than an id, matching a whole family at once. There are eighty-eight
 * spawn eggs and a new one arrives with most mobs, so listing them by hand is a list that is
 * wrong by the next update. {@code #spawn_eggs} is never wrong.
 */
public final class IllegalItems {
	private IllegalItems() {}

	/** Matches every spawn egg, present and future, including modded ones. */
	public static final String RULE_SPAWN_EGGS = "#spawn_eggs";

	/**
	 * Blocks and items that cannot be obtained in survival at all.
	 * <p>
	 * Every id here is checked against the real registry by the test suite, because a typo
	 * is silent: an id that resolves to nothing simply never matches, and the entry looks
	 * like protection while providing none. {@code minecraft:spawn_egg} sat here for exactly
	 * that reason — it is not an item, so no spawn egg was ever flagged.
	 */
	public static List<String> creativeOnlyDefaults() {
		return new ArrayList<>(List.of(
				RULE_SPAWN_EGGS,

				// Unbreakable or unobtainable world structure.
				"minecraft:bedrock",
				"minecraft:barrier",
				"minecraft:light",
				"minecraft:structure_void",
				"minecraft:end_portal_frame",
				"minecraft:reinforced_deepslate",
				"minecraft:budding_amethyst",
				"minecraft:dragon_egg",
				"minecraft:spawner",
				"minecraft:trial_spawner",
				"minecraft:vault",

				// Blocks with no survival drop of themselves.
				"minecraft:petrified_oak_slab",
				"minecraft:farmland",
				"minecraft:dirt_path",
				"minecraft:chorus_plant",
				"minecraft:suspicious_sand",
				"minecraft:suspicious_gravel",

				// Silverfish blocks — a griefing tool more than a building one.
				"minecraft:infested_stone",
				"minecraft:infested_cobblestone",
				"minecraft:infested_deepslate",
				"minecraft:infested_stone_bricks",
				"minecraft:infested_mossy_stone_bricks",
				"minecraft:infested_cracked_stone_bricks",
				"minecraft:infested_chiseled_stone_bricks"
		));
	}

	/** Operator-only tooling — the presence of any of these is a serious finding. */
	public static List<String> operatorOnlyDefaults() {
		return new ArrayList<>(List.of(
				"minecraft:command_block",
				"minecraft:chain_command_block",
				"minecraft:repeating_command_block",
				"minecraft:command_block_minecart",
				"minecraft:structure_block",
				"minecraft:jigsaw",
				"minecraft:debug_stick",
				"minecraft:knowledge_book",
				"minecraft:test_block",
				"minecraft:test_instance_block"
		));
	}

	/**
	 * A compiled list: ids resolved once, rules kept as flags, unknown names remembered.
	 * <p>
	 * Resolution used to happen per stack, which meant parsing the whole configured list
	 * against the registry for every slot of every inventory scanned. Compiling once and
	 * holding the result turns the hot path back into a set lookup.
	 *
	 * @param unknown configured names that matched nothing, so they can be reported rather
	 *                than silently doing nothing
	 */
	public record Ruleset(Set<Item> explicit, boolean spawnEggs, List<String> unknown) {

		public static final Ruleset EMPTY = new Ruleset(Set.of(), false, List.of());

		public boolean matches(ItemStack stack) {
			return stack != null && !stack.isEmpty() && matches(stack.getItem());
		}

		/** The same question about the item type alone, with no stack to hand. */
		public boolean matches(Item item) {
			if (item == null) return false;
			if (explicit.contains(item)) return true;
			return spawnEggs && item instanceof SpawnEggItem;
		}

		/** Roughly how many items this covers, for the config screen. */
		public int size() {
			return explicit.size() + (spawnEggs ? spawnEggCount() : 0);
		}

		public boolean isEmpty() {
			return explicit.isEmpty() && !spawnEggs;
		}
	}

	/** Compiles configured names into something the per-stack check can use. */
	public static Ruleset compile(List<String> names) {
		if (names == null || names.isEmpty()) return Ruleset.EMPTY;

		Set<Item> explicit = new HashSet<>();
		List<String> unknown = new ArrayList<>();
		boolean spawnEggs = false;

		for (String name : names) {
			if (name == null || name.isBlank()) continue;
			String trimmed = name.trim();

			if (trimmed.startsWith("#")) {
				if (RULE_SPAWN_EGGS.equalsIgnoreCase(trimmed)) spawnEggs = true;
				else unknown.add(trimmed);
				continue;
			}

			Item item = Mc.itemFromId(trimmed, null);
			if (item == null) unknown.add(trimmed);
			else explicit.add(item);
		}
		return new Ruleset(explicit, spawnEggs, List.copyOf(unknown));
	}

	/**
	 * Says out loud which configured names match nothing.
	 * <p>
	 * Silence here is the failure mode worth designing against: an admin who mistypes an id
	 * gets a list that looks longer than it is, and finds out when somebody walks past with
	 * the item they thought they had banned.
	 */
	public static void report(String label, Ruleset ruleset) {
		if (ruleset.unknown().isEmpty()) return;
		StaffCore.LOGGER.warn("[Security] {} contains {} name(s) that match no item and will "
						+ "never flag anything: {}",
				label, ruleset.unknown().size(), String.join(", ", ruleset.unknown()));
	}

	/** How many spawn eggs this version has — counted once, for reporting. */
	public static int spawnEggCount() {
		if (spawnEggs < 0) {
			int found = 0;
			for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				if (item instanceof SpawnEggItem) found++;
			}
			spawnEggs = found;
		}
		return spawnEggs;
	}

	private static int spawnEggs = -1;
}
