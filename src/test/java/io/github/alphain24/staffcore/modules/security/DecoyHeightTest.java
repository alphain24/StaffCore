package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoys go where the game puts diamonds: the bottom of the world up to Y 16, thickest at the
 * bottom, and nearly all of it deepslate.
 * <p>
 * The numbers pinned here are the game's own, from {@code worldgen/placed_feature/ore_diamond*}
 * in 26.2, for the overworld's bottom of Y -64.
 */
class DecoyHeightTest {

	private static final int BOTTOM = -64;

	@Test
	@DisplayName("no diamonds above Y 16 or below the world, and the most at the bottom")
	void densityFollowsTheGame() {
		assertEquals(0, Canaries.diamondDensity(BOTTOM, 17));
		assertEquals(0, Canaries.diamondDensity(BOTTOM, BOTTOM - 1));
		assertTrue(Canaries.diamondDensity(BOTTOM, 16) < 0.001, "Y 16 is the very top of the range");

		double previous = Double.MAX_VALUE;
		for (int y = BOTTOM; y <= 16; y++) {
			double here = Canaries.diamondDensity(BOTTOM, y);
			assertTrue(here <= previous, "density should only fall going up, but rose at Y " + y);
			previous = here;
		}
	}

	@Test
	@DisplayName("about nineteen diamonds in twenty are below Y 0, where the rock is deepslate")
	void mostDiamondsAreDeepslate() {
		double below = 0;
		double all = 0;
		for (int y = BOTTOM; y <= 16; y++) {
			double here = Canaries.diamondDensity(BOTTOM, y);
			all += here;
			if (y < 0) below += here;
		}
		assertTrue(below / all > 0.9, "only " + Math.round(100 * below / all) + "% below Y 0");
	}

	@Test
	@DisplayName("the band is where diamonds are and near the player, and empty on the surface")
	void bandIsNearThePlayerAndInTheDiamondLayer() {
		assertNull(Canaries.heightBand(BOTTOM, 64, 16), "a player on the surface should have none");
		assertArrayEquals(new int[] {BOTTOM, -46}, Canaries.heightBand(BOTTOM, -58, 16));
		assertArrayEquals(new int[] {-22, 16}, Canaries.heightBand(BOTTOM, 10, 16));
		assertArrayEquals(new int[] {-28, 16}, Canaries.heightBand(BOTTOM, 4, 100),
				"canaryMaxY above 16 must not put decoys where diamonds never generate");
	}

	@Test
	@DisplayName("a player strip-mining at Y 10 is mostly given deepslate decoys")
	void aPlayerNearTheDeepslateLineGetsMostlyDeepslate() {
		Random random = new Random(26);
		int[] band = Canaries.heightBand(BOTTOM, 10, 16);
		double top = Canaries.diamondDensity(BOTTOM, BOTTOM);

		int kept = 0;
		int deepslate = 0;
		for (int i = 0; i < 100_000; i++) {
			int y = band[0] + random.nextInt(band[1] - band[0] + 1);
			if (random.nextDouble() * top > Canaries.diamondDensity(BOTTOM, y)) continue;
			kept++;
			if (y < 0) deepslate++;
		}
		assertTrue(deepslate > kept * 0.6, "only " + deepslate + " of " + kept + " below Y 0");
	}
}
