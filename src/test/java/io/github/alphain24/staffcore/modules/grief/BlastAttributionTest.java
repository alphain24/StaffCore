package io.github.alphain24.staffcore.modules.grief;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Naming the player behind the explosions vanilla leaves unnamed — and refusing to guess.
 */
class BlastAttributionTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	private static final BlastAttribution.Culprit STEVE =
			new BlastAttribution.Culprit(UUID.randomUUID(), "Steve", "TNT");
	private static final BlastAttribution.Culprit ALEX =
			new BlastAttribution.Culprit(UUID.randomUUID(), "Alex", "TNT");

	@Test
	@DisplayName("TNT primed by redstone belongs to whoever placed the block")
	void placedTntIsThePlacers() {
		BlastAttribution blasts = new BlastAttribution();
		BlockPos pos = new BlockPos(10, 64, -3);
		UUID tnt = UUID.randomUUID();

		blasts.onTntPlaced(OVERWORLD, pos, STEVE, 0);
		blasts.onTntPrimed(tnt, OVERWORLD, pos, 5_000);

		BlastAttribution.Culprit who = blasts.takePrimed(tnt);
		assertEquals("Steve", who.name());
		assertEquals("TNT they placed", who.how(),
				"the alert has to say this was inferred from placing, not seen lighting");
		assertNull(blasts.takePrimed(tnt), "a TNT explodes once");
	}

	@Test
	@DisplayName("the same coordinates in another dimension are another block")
	void dimensionsAreSeparate() {
		BlastAttribution blasts = new BlastAttribution();
		BlockPos pos = new BlockPos(0, 70, 0);
		UUID tnt = UUID.randomUUID();

		blasts.onTntPlaced(OVERWORLD, pos, STEVE, 0);
		blasts.onTntPrimed(tnt, NETHER, pos, 1);

		assertNull(blasts.takePrimed(tnt));
	}

	@Test
	@DisplayName("TNT placed over an hour ago is not pinned on its placer")
	void oldPlacementsDoNotCount() {
		BlastAttribution blasts = new BlastAttribution();
		BlockPos pos = new BlockPos(1, 2, 3);
		UUID tnt = UUID.randomUUID();

		blasts.onTntPlaced(OVERWORLD, pos, STEVE, 0);
		blasts.onTntPrimed(tnt, OVERWORLD, pos, BlastAttribution.PLACEMENT_MS + 1);

		assertNull(blasts.takePrimed(tnt));
	}

	@Test
	@DisplayName("a placement is used once: the next TNT at that spot needs its own placer")
	void aPlacementIsConsumed() {
		BlastAttribution blasts = new BlastAttribution();
		BlockPos pos = new BlockPos(4, 5, 6);

		blasts.onTntPlaced(OVERWORLD, pos, STEVE, 0);
		blasts.onTntPrimed(UUID.randomUUID(), OVERWORLD, pos, 1);
		UUID second = UUID.randomUUID();
		blasts.onTntPrimed(second, OVERWORLD, pos, 2);

		assertNull(blasts.takePrimed(second));
	}

	@Test
	@DisplayName("remembered placements are bounded, oldest forgotten first")
	void bounded() {
		BlastAttribution blasts = new BlastAttribution();
		for (int i = 0; i <= BlastAttribution.MEMORY; i++) {
			blasts.onTntPlaced(OVERWORLD, new BlockPos(i, 0, 0), STEVE, 0);
		}

		UUID first = UUID.randomUUID();
		blasts.onTntPrimed(first, OVERWORLD, new BlockPos(0, 0, 0), 1);
		assertNull(blasts.takePrimed(first), "the eldest placement should have been dropped");

		UUID last = UUID.randomUUID();
		blasts.onTntPrimed(last, OVERWORLD, new BlockPos(BlastAttribution.MEMORY, 0, 0), 1);
		assertEquals("Steve", blasts.takePrimed(last).name());
	}

	@Test
	@DisplayName("a bed blast belongs to the click on it in the same tick")
	void aClickExplainsABlastBesideIt() {
		BlastAttribution blasts = new BlastAttribution();
		// Clicked the foot of the bed; it explodes at the head, one block over.
		blasts.onUse(new BlastAttribution.Use(NETHER, new BlockPos(0, 64, 0), 100, STEVE));

		assertEquals("Steve", blasts.clickedNear(NETHER, 1.5, 64.5, 0.5, 100).name());
	}

	@Test
	@DisplayName("a click in an earlier tick, somewhere else, or elsewhere in the world explains nothing")
	void clicksMustLineUp() {
		BlastAttribution blasts = new BlastAttribution();
		blasts.onUse(new BlastAttribution.Use(NETHER, new BlockPos(0, 64, 0), 100, STEVE));

		assertNull(blasts.clickedNear(NETHER, 0.5, 64.5, 0.5, 101), "a later tick");
		assertNull(blasts.clickedNear(OVERWORLD, 0.5, 64.5, 0.5, 100), "another dimension");
		assertNull(blasts.clickedNear(NETHER, 5.5, 64.5, 0.5, 100), "too far away");
	}

	@Test
	@DisplayName("two players clicking beside the same blast: nobody is named")
	void ambiguityNamesNobody() {
		BlastAttribution blasts = new BlastAttribution();
		blasts.onUse(new BlastAttribution.Use(NETHER, new BlockPos(0, 64, 0), 100, STEVE));
		blasts.onUse(new BlastAttribution.Use(NETHER, new BlockPos(1, 64, 0), 100, ALEX));

		assertNull(blasts.clickedNear(NETHER, 0.5, 64.5, 0.5, 100));
	}
}
