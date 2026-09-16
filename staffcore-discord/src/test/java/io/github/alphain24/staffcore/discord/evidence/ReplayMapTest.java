package io.github.alphain24.staffcore.discord.evidence;

import io.github.alphain24.staffcore.api.DiscordReplayTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replay drawn as a map: a real PNG, of the dimension the player spent their time in, with the words
 * that explain it.
 */
class ReplayMapTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final long T0 = 1_700_000_000_000L;

	private static DiscordReplayTrack track() {
		List<DiscordReplayTrack.Point> points = new ArrayList<>();
		for (int i = 0; i < 200; i++) points.add(new DiscordReplayTrack.Point(T0 + i * 500L, OVERWORLD, 100 + i * 0.5, 12, -40));
		// A teleport: a jump the map must not draw as a path.
		points.add(new DiscordReplayTrack.Point(T0 + 200 * 500L, OVERWORLD, 900, 12, 900));
		points.add(new DiscordReplayTrack.Point(T0 + 201 * 500L, "minecraft:the_nether", 10, 70, 10));
		List<DiscordReplayTrack.Change> changes = List.of(
				new DiscordReplayTrack.Change(T0 + 1000, OVERWORLD, 120, 12, -39, true, "minecraft:deepslate_diamond_ore"),
				new DiscordReplayTrack.Change(T0 + 2000, OVERWORLD, 121, 12, -39, true, "minecraft:deepslate_diamond_ore"),
				new DiscordReplayTrack.Change(T0 + 3000, OVERWORLD, 122, 12, -39, true, "minecraft:deepslate"),
				new DiscordReplayTrack.Change(T0 + 4000, OVERWORLD, 123, 12, -39, false, "minecraft:torch"));
		return new DiscordReplayTrack(UUID.randomUUID(), "Steve_", T0, T0 + 3_600_000L, points, changes, 2, false, false);
	}

	@Test
	@DisplayName("the map is a readable PNG of the dimension the player spent most time in")
	void renders() throws Exception {
		ReplayMap.Drawn drawn = ReplayMap.render(track());
		assertEquals(OVERWORLD, drawn.world());
		assertEquals(1, drawn.otherWorldPoints());
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(drawn.png()));
		assertEquals(ReplayMap.SIZE, image.getWidth());
		assertEquals(ReplayMap.SIZE, image.getHeight());
		assertTrue(drawn.minX() <= 100 && drawn.maxX() >= 900, drawn.minX() + " to " + drawn.maxX());

		// Something other than the background was drawn.
		int background = image.getRGB(1, 1);
		boolean drewSomething = false;
		for (int x = 0; x < image.getWidth() && !drewSomething; x += 7) {
			for (int y = 0; y < image.getHeight(); y += 7) {
				if (image.getRGB(x, y) != background) {
					drewSomething = true;
					break;
				}
			}
		}
		assertTrue(drewSomething, "the map is blank");
	}

	@Test
	@DisplayName("the words say the window, the scale, what was broken and that other dimensions are left out")
	void summary() throws Exception {
		DiscordReplayTrack track = track();
		String text = ReplayMap.summary(track, ReplayMap.render(track));
		assertTrue(text.contains("Steve_"), text);
		assertTrue(text.contains("3 broken"), text);
		assertTrue(text.contains("1 placed"), text);
		assertTrue(text.contains("2× deepslate_diamond_ore"), text);
		assertTrue(text.contains("north is up"), text);
		assertTrue(text.contains("other dimensions"), text);
		assertTrue(text.contains("Gaps in the line are real"), text);
		assertTrue(text.contains("<t:" + T0 / 1000 + ":f>"), text);
	}

	@Test
	@DisplayName("a player who never moved is still drawn")
	void standingStill() throws Exception {
		DiscordReplayTrack still = new DiscordReplayTrack(UUID.randomUUID(), "Alex", T0, T0 + 60_000L,
				List.of(new DiscordReplayTrack.Point(T0, OVERWORLD, 5, 64, 5)), List.of(), 1, false, false);
		ReplayMap.Drawn drawn = ReplayMap.render(still);
		assertTrue(drawn.png().length > 0);
		assertEquals("replay-alex.png", ReplayMap.fileName(still));
	}

	@Test
	@DisplayName("grid and scale steps are round numbers")
	void steps() {
		assertEquals(1, ReplayMap.niceStep(0.3));
		assertEquals(2, ReplayMap.niceStep(1.5));
		assertEquals(5, ReplayMap.niceStep(3));
		assertEquals(10, ReplayMap.niceStep(7));
		assertEquals(20, ReplayMap.niceStep(11));
		assertEquals(500, ReplayMap.niceStep(260));
		assertTrue(ReplayMap.isOre("minecraft:ancient_debris"));
		assertTrue(!ReplayMap.isOre("minecraft:stone"));
	}
}
