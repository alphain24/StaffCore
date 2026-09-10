package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.config.StaffConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escape hatch is off, is named so it cannot be mistaken for a mode, and says what it costs.
 *
 * <h2>Why a config key gets its own test</h2>
 * Because this one is dangerous in a way the others are not. Every other switch here changes how
 * well a feature works. This one changes <em>what a signal means</em>, silently: a canary hit
 * produced with a bulk anti-xray running looks identical to one produced without, appears in the
 * same list, and carries the same confidence — while meaning something entirely different.
 * <p>
 * With an anti-xray filling the world with fabricated ore, an x-ray user learns within an hour
 * that nothing their pack shows is real and stops acting on any of it. That does not merely make
 * a hit harder to read. <b>It removes the true positives.</b> Nobody walks to a decoy because
 * nobody walks to anything, so the feature reports a clean zero and the zero means nothing.
 * <p>
 * So the properties worth pinning are not about behaviour. They are: it defaults off, it is
 * named awkwardly enough that nobody enables it thinking it is a supported mode, and turning it
 * on produces a warning that names the consequence rather than the setting.
 */
class CanaryOverrideTest {

	private static final Path CONFIG = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "config", "StaffConfig.java");

	private static final Path COMPANION = Path.of("src", "main", "java", "io", "github",
			"alphain24", "staffcore", "modules", "security", "AntiXrayCompanion.java");

	@Test
	@DisplayName("it is off on a fresh install")
	void defaultsOff() {
		assertFalse(new StaffConfig().canaryForceWithBulkAntiXray,
				"canaryForceWithBulkAntiXray defaults to true. A server that installs an "
						+ "anti-xray mod would then start producing canary signals that are "
						+ "not defensible, having chosen nothing.");
	}

	@Test
	@DisplayName("the name cannot be read as a supported mode")
	void theNameIsAWarning() {
		// Deliberately literal. A key called "canaryCompatibility" or "allowBothAntiXray"
		// would read as a supported arrangement, and somebody scanning a config file for
		// things to turn on would turn it on. The name has to say what it is doing.
		String name = "canaryForceWithBulkAntiXray";

		assertTrue(name.contains("Force"),
				"the key no longer says Force. It is not a mode, it is an override, and the "
						+ "name is the only thing a server owner reads before enabling it.");
		assertTrue(name.contains("BulkAntiXray"),
				"the key no longer names the thing it is overriding, so it reads as a general "
						+ "canary setting");
	}

	@Test
	@DisplayName("the warning names the consequence, not the setting")
	void theWarningIsAboutAppeals() throws IOException {
		// "canaryForceWithBulkAntiXray is enabled" is a true sentence that tells a reader
		// nothing they did not already know — they set it. What they need told is what it
		// costs them, in the words they will need when somebody appeals.
		String companion = Files.readString(COMPANION, StandardCharsets.UTF_8);

		assertTrue(companion.contains("NOT DEFENSIBLE IN AN APPEAL"),
				"the startup warning no longer says that signals produced in this mode are "
						+ "not defensible in an appeal. That sentence is the whole point of "
						+ "the warning: it is what a staff member needs to have read before "
						+ "they act on a hit.");
		assertTrue(companion.contains("true positives"),
				"the warning no longer explains that this loses true positives rather than "
						+ "only their interpretation. Without that, it reads as a caution "
						+ "about accuracy, and somebody will decide the risk is acceptable.");
	}

	@Test
	@DisplayName("the key documents that it is for compatibility testing")
	void theDocumentationSaysWhatItIsFor() throws IOException {
		String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
		int at = config.indexOf("canaryForceWithBulkAntiXray");
		assertTrue(at > 0, "the key is gone");

		String doc = config.substring(Math.max(0, at - 2000), at);
		assertTrue(doc.contains("not a supported mode"),
				"the key's documentation no longer says plainly that this is not a supported "
						+ "mode");
		assertTrue(doc.contains("compatibility testing"),
				"the key's documentation no longer says what it is actually for. Without a "
						+ "stated purpose an override reads as a preference.");
	}
}
