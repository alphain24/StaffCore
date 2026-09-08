package io.github.alphain24.staffcore.modules.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection and prevention are different halves, and the mod has to say which one it is doing.
 * <p>
 * The failure worth guarding against is silence. A server with no anti-xray installed and no
 * line saying so reads as "prevention is handled" — which is exactly the assumption that gets
 * somebody to install this mod, watch it report nothing for a month, and conclude nobody is
 * cheating.
 * <p>
 * The mod list itself cannot be varied inside a unit test, so what is checked here is the
 * reasoning either side of it: that both answers produce a sentence, that the sentence for
 * "none installed" does not read as reassurance, and that the documentation names the same
 * mods the code looks for.
 */
class AntiXrayCompanionTest {

	@Test
	@DisplayName("both answers produce a line, because silence reads as reassurance")
	void neitherOutcomeIsSilent() {
		String line = AntiXrayCompanion.startupLine();

		assertNotNull(line);
		assertFalse(line.isBlank(),
				"a blank line is how an admin concludes prevention is handled when it is not");
		assertTrue(line.startsWith("Anti-xray:"), "the line should say what it is about");
	}

	@Test
	@DisplayName("with none installed, the line says what StaffCore does not do")
	void theAbsentCaseIsExplicit() {
		// No anti-xray mod is on the test classpath, so this is the case under test — and it
		// is the common one on a real server, which is why the wording matters more than the
		// other branch's.
		assertTrue(AntiXrayCompanion.installed().isEmpty(),
				"an anti-xray mod appeared on the test classpath; this test needs updating");

		String line = AntiXrayCompanion.startupLine();
		assertTrue(line.contains("does not prevent"),
				"the line has to say StaffCore only detects, or it reads as coverage: " + line);
	}

	@Test
	@DisplayName("canaries stay on when nothing else is rewriting chunks")
	void noCompanionMeansNoReasonToDisable() {
		assertFalse(AntiXrayCompanion.present());
		assertNull(AntiXrayCompanion.whyCanariesAreOff(),
				"canaries were switched off with nothing to conflict with");
	}

	@Test
	@DisplayName("the README names exactly the mods the code looks for")
	void documentationAndCodeCannotDrift() {
		// Two lists of the same thing, in two files, is how a mod gets added to one and not
		// the other — and the direction that hurts is the code knowing about a mod the docs
		// never recommend, because then nobody installs it and the detection never fires.
		String readme = read(Path.of("README.md"));

		for (String id : AntiXrayCompanion.knownIds()) {
			assertTrue(readme.contains(idToRepo(id)),
					"the code detects \"" + id + "\" and the README does not recommend it. "
							+ "Detection nobody was told to trigger is detection that never runs.");
		}
	}

	@Test
	@DisplayName("the licences are recorded, because vendoring one would have been a decision")
	void theLicenceFindingIsWrittenDown() {
		// The brief required checking these before writing a line, on the grounds that
		// vendoring a GPL implementation would relicense this whole project. Both turned out
		// permissive, which means the finding is easy to forget — and the next person
		// wondering why StaffCore does not obfuscate chunks deserves the real reason rather
		// than an assumption that it was a licence problem.
		String source = read(Path.of("src", "main", "java", "io", "github", "alphain24",
				"staffcore", "modules", "security", "AntiXrayCompanion.java"));

		assertTrue(source.contains("MIT"),
				"the licence finding is not recorded beside the decision it informed");
		for (String phrase : List.of("Drex", "xiaoyiluck")) {
			assertTrue(source.contains(phrase),
					"the copyright holder of " + phrase + "'s work is not named");
		}
	}

	private static String idToRepo(String id) {
		return switch (id) {
			case "antixray" -> "DrexHD/AntiXray";
			case "meowantixray" -> "MeowAnti-Xray";
			default -> id;
		};
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("could not read " + path, e);
		}
	}
}
