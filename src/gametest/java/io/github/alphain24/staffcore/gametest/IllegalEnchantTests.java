package io.github.alphain24.staffcore.gametest;

import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.SecurityModule;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Items that are impossible because of what is on them, not what they are.
 */
public class IllegalEnchantTests {

	private static ItemStack sword(GameTestHelper helper, int sharpness) {
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
				.getOrThrow(Enchantments.SHARPNESS), sharpness);
		return sword;
	}

	@GameTest
	public void onlyLevelsThatCannotExistAreFlagged(GameTestHelper helper) {
		Harness.check(helper, SecurityModule.impossibleTraits(sword(helper, 5), "inventory").isEmpty(),
				"Sharpness V is the vanilla maximum and was flagged");
		Harness.check(helper, !SecurityModule.impossibleTraits(sword(helper, 50), "inventory").isEmpty(),
				"Sharpness 50 was not flagged");

		// Where anything worth hiding goes.
		ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
		shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sword(helper, 50))));
		Harness.check(helper, !SecurityModule.impossibleTraits(shulker, "inventory").isEmpty(),
				"a Sharpness 50 sword inside a shulker box was not flagged");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void theAutomaticWatchReportsAnIllegalEnchantment(GameTestHelper helper) {
		ServerPlayer player = Harness.mockPlayer(helper);
		player.getInventory().add(sword(helper, 255));

		// The watch runs on its own every couple of seconds; nothing is asked of it here. The
		// finding lands as a contraband signal against the player.
		helper.succeedWhen(() -> Harness.check(helper,
				Mods.cases().store().unattachedFor(player.getUUID(), 20).stream()
						.anyMatch(signal -> signal.evidenceJson() != null
								&& signal.evidenceJson().contains("255")),
				"no signal about the Sharpness 255 sword yet"));
	}
}
