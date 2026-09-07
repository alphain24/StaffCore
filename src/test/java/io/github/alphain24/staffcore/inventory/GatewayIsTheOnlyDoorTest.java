package io.github.alphain24.staffcore.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing outside {@link InventoryGateway} writes to a player's inventory.
 * <p>
 * The gateway was introduced with four callers routed through it, and that was described
 * afterwards as "the only path that writes to inventories". It was not. Five more writes were
 * still going round it — the staff-mode stash clearing and restoring an entire inventory, two
 * confiscation paths, and the leaked-staff-tool sweep — and none of them were recorded. The
 * asymmetry was at its worst in the vault, which audited handing an item <em>back</em> through
 * the gateway while taking it in the first place went unrecorded.
 * <p>
 * A door with five side entrances is not a door, and the gap did not appear through
 * carelessness: it appeared because "the four paths I routed" and "every path" are easy to
 * confuse when the list is in your head. So the rule is checked mechanically rather than
 * remembered, and this test is the reason the claim can be made at all.
 * <p>
 * <b>What is deliberately outside.</b> The rule is about a <em>player's</em> inventory being
 * written by StaffCore's own decision. Three things are exempt and each is exempt for a
 * reason, listed in {@link #ALLOWED}:
 * <ul>
 *   <li><b>World containers.</b> Chests are not player inventories. Rollback refills them
 *       constantly and auditing that would be an audit of the rollback, which already has
 *       one.</li>
 *   <li><b>Mixins intercepting vanilla.</b> {@code InventoryDropMixin} stops staff tools
 *       reaching the ground inside vanilla's own death handling. It is a containment layer on
 *       a path StaffCore did not initiate, and a database write on every death would be a
 *       poor trade.</li>
 *   <li><b>The staff toolset.</b> Handing somebody their own tools on clocking on is not an
 *       economy event: the tools are refused on drop, stripped on death and destroyed on the
 *       ground, so they cannot leak into the world.</li>
 * </ul>
 */
class GatewayIsTheOnlyDoorTest {

	private static final Path SOURCE = Path.of("src", "main", "java");

	/**
	 * Calls that write into a container, as they appear in source.
	 * <p>
	 * Deliberately textual. A call graph would be more precise and would need the thing it is
	 * checking to be correct in order to work; grep does not.
	 */
	private static final Pattern WRITE = Pattern.compile(
			"\\.(setItem|clearContent|removeItemNoUpdate|placeItemBackInInventory)\\s*\\(");

	/** Files permitted to write directly, each with the reason it is exempt. */
	private static final Set<String> ALLOWED = Set.of(
			// The gateway itself.
			"inventory/InventoryGateway.java",

			// Vanilla's own death-drop path, intercepted to keep staff tools out of the world.
			"mixin/InventoryDropMixin.java",

			// World containers, not player inventories.
			"modules/grief/ContainerWatch.java",
			"modules/grief/GriefModule.java",
			"modules/grief/RollbackPoints.java",

			// Staff tools, which cannot enter the economy.
			"modules/staffmode/StaffToolset.java");

	/** Menus paint their own scratch slots constantly and never touch a real inventory. */
	private static boolean isMenuPainting(String relative) {
		return relative.startsWith("gui/");
	}

	private record Offence(String file, int line, String text) {}

	/**
	 * The opt-out marker, which has to sit within a few lines of the write.
	 * <p>
	 * Deliberately something a person has to type next to the code, carrying a reason. The
	 * alternative is a list in this file naming other files, which drifts out of date without
	 * anybody noticing and cannot say <em>why</em> a particular line is allowed.
	 */
	private static final String MARKER = "gateway-exempt:";

	private static boolean exemptedNearby(List<String> lines, int index) {
		for (int i = Math.max(0, index - 4); i <= index; i++) {
			if (lines.get(i).contains(MARKER)) return true;
		}
		return false;
	}

	@Test
	@DisplayName("no direct container writes outside the gateway and its documented exemptions")
	void everyInventoryWriteGoesThroughTheGateway() throws IOException {
		List<Offence> offences = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = SOURCE.relativize(file).toString().replace('\\', '/')
						.replaceFirst("^io/github/alphain24/staffcore/", "");

				if (ALLOWED.contains(relative) || isMenuPainting(relative)) continue;

				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					Matcher m = WRITE.matcher(line);
					if (!m.find()) continue;

					// A one-off write can opt out by saying so at the call site. Matching on
					// the variable name instead — "container", say — was the first attempt and
					// was worse: it would have silently exempted any future write to a
					// player's inventory that happened to use the same name.
					if (exemptedNearby(lines, i)) continue;

					offences.add(new Offence(relative, i + 1, line.trim()));
				}
			}
		}

		assertTrue(offences.isEmpty(), () -> "these write to an inventory without going through "
				+ "InventoryGateway. Route them, or add them to ALLOWED with the reason:\n  "
				+ String.join("\n  ", offences.stream()
						.map(o -> o.file() + ":" + o.line() + "  " + o.text()).toList()));
	}

	@Test
	@DisplayName("the exemption list stays short, and every entry still exists")
	void exemptionsAreRealAndFew() {
		for (String allowed : ALLOWED) {
			assertTrue(Files.exists(SOURCE.resolve("io/github/alphain24/staffcore").resolve(allowed)),
					allowed + " is exempted but no longer exists — the list has gone stale, and "
							+ "a stale exemption list is how the next bypass gets in unnoticed");
		}

		// Not a style preference. Every entry here is a place the audit trail has a hole, and
		// a list that grows without anybody noticing is how "the only door" stopped being true
		// the first time.
		assertTrue(ALLOWED.size() <= 8,
				"the exemption list has grown to " + ALLOWED.size() + ". Each one is a gap in "
						+ "the record; if this many are genuinely needed the rule needs "
						+ "rethinking rather than widening");
	}
}
