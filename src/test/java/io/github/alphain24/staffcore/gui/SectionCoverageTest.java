package io.github.alphain24.staffcore.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every screen is still reachable after the panel was split into sections.
 *
 * <h2>The failure this is for</h2>
 * The staff panel was twenty-five buttons on one flat screen. Splitting it into sections means
 * moving every one of those destinations somewhere else, and the way that goes wrong is
 * <b>silent</b>: a menu that nothing opens any more still compiles, still has all its tests,
 * and simply cannot be reached by anybody. Nobody files a bug for a screen they have forgotten
 * exists.
 * <p>
 * So this asks the blunt question — is there a menu in the package that no other menu opens?
 * It is deliberately crude: it greps for {@code SomeMenu.open} and {@code SomeMenu::open}
 * across the GUI package. A grep cannot know whether the call is on a path anybody can walk,
 * but it catches the case that actually happens, which is a destination nobody kept.
 *
 * <h2>Why the entry points are listed rather than inferred</h2>
 * Some screens are opened from a command or an event rather than from another menu, and a few
 * are the top of the tree. Those are named below with the reason, so "unreachable from the GUI"
 * stays a meaningful finding instead of a list of known exceptions somebody learns to ignore.
 */
class SectionCoverageTest {

	private static final Path MENUS = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "gui", "menu");

	private static final Path SOURCE = Path.of("src", "main", "java");

	/**
	 * Screens that are legitimately not opened by another menu.
	 * <p>
	 * Each is here because something other than a menu opens it, not because it is allowed to
	 * be orphaned.
	 */
	private static final Set<String> ENTRY_POINTS = new LinkedHashSet<>(List.of(
			// The top of the tree. Opened by /staff and by the panel item.
			"StaffPanelMenu",
			// Opened by name from every section rather than from a menu class.
			"SectionMenu",
			// Not a screen: the draft a punishment is assembled into as it crosses menus.
			"PunishDraft",
			// Not a screen either: the definitions of what each section contains. Reached as
			// StaffSections.players and so on, which the section test below checks properly.
			"StaffSections",
			// Opened by /staff invsee, /staff seccheck and the player-actions screen, and by
			// the inspect-mode block click rather than from a fixed menu button.
			"BlockHistoryMenu",
			"ContainerLogMenu"));

	private static List<String> menuNames() throws IOException {
		try (Stream<Path> files = Files.list(MENUS)) {
			return files.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".java"))
					.map(n -> n.substring(0, n.length() - ".java".length()))
					.sorted()
					.toList();
		}
	}

	/** Every line of every source file, so a reference anywhere counts. */
	private static String allSource() throws IOException {
		StringBuilder out = new StringBuilder();
		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				out.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
			}
		}
		return out.toString();
	}

	@Test
	@DisplayName("no screen was orphaned by the split into sections")
	void everyMenuIsOpenedFromSomewhere() throws IOException {
		String source = allSource();
		List<String> orphans = new ArrayList<>();

		for (String menu : menuNames()) {
			if (ENTRY_POINTS.contains(menu)) continue;

			// Either form counts: an explicit call or a method reference passed as the action.
			boolean referenced = source.contains(menu + ".open(")
					|| source.contains(menu + "::open")
					|| source.contains(menu + ".openFor");

			if (!referenced) orphans.add(menu);
		}

		assertTrue(orphans.isEmpty(),
				"screen(s) nothing opens any more: " + orphans + ".\n\nThe panel was split "
						+ "into sections, and a destination that did not get a new home is "
						+ "unreachable while still compiling and still passing its own tests. "
						+ "Put it in a section in StaffSections, or delete it — an unreachable "
						+ "screen is worse than a missing one, because it looks like a feature.");
	}

	@Test
	@DisplayName("the scan would notice an orphan, so a pass means something")
	void theScanIsNotVacuous() throws IOException {
		// This test passes by finding nothing, which is the shape this project keeps being
		// caught by. Checking that the search actually resolves real names is not optional.
		List<String> menus = menuNames();
		assertTrue(menus.size() > 20,
				"only " + menus.size() + " menus were found, so the directory scan is looking "
						+ "in the wrong place and the test above proves nothing");

		String source = allSource();
		assertTrue(source.contains("CasesMenu::open") || source.contains("CasesMenu.open("),
				"the source scan cannot find a reference that is definitely there, so it would "
						+ "report every screen as an orphan or none of them");
		assertTrue(!source.contains("ThisMenuDoesNotExistMenu.open("),
				"the scan matched a name that does not exist");
	}

	@Test
	@DisplayName("every section is reachable from the panel")
	void everySectionHasAButton() throws IOException {
		// The other direction. A section defined in StaffSections that the panel never opens
		// is the same orphan one level up, and it is easier to create: adding the method is
		// the interesting part and wiring the button is the part somebody forgets.
		String sections = Files.readString(
				MENUS.resolve("StaffSections.java"), StandardCharsets.UTF_8);
		String panel = Files.readString(
				MENUS.resolve("StaffPanelMenu.java"), StandardCharsets.UTF_8);

		List<String> missing = new ArrayList<>();
		for (String name : List.of("players", "punishments", "security", "antiCheat", "world",
				"server", "discord")) {

			assertTrue(sections.contains("public static void " + name + "("),
					"StaffSections." + name + " has gone; this test is now checking a section "
							+ "that does not exist");

			if (!panel.contains("StaffSections." + name + "(")) missing.add(name);
		}

		assertTrue(missing.isEmpty(),
				"section(s) with no button on the panel: " + missing
						+ ". They exist and nobody can get to them.");
	}
}
