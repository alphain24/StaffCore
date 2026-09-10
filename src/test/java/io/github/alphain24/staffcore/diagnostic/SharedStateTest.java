package io.github.alphain24.staffcore.diagnostic;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing shared and mutable gets added without somebody deciding to.
 *
 * <h2>The failure this is for</h2>
 * Gametests inside a batch run <b>at the same time</b>, in different parts of the same world, on
 * the same server, against the same singletons. Seven test classes each opened and closed with a
 * global {@code forgetAll()} that cleared canary and illusion state for every player at once, so
 * one test's tidy-up could wipe another test's decoys part way through its assertions. That
 * produced a failure about one run in eight, in whichever test happened to be unlucky, and it
 * reads as flakiness rather than as a shared-state bug.
 * <p>
 * The instances were fixable in an afternoon. The class is not, because the next one will be
 * written by somebody who has never seen this happen, and it is invisible in review: a
 * {@code forgetAll()} in a teardown looks like tidiness.
 *
 * <h2>Two directions, because one would miss half of it</h2>
 * <b>Down</b>: the tests must not reach for the specific things that are shared across
 * concurrently-running tests — the config singleton, and the global reset methods.
 * <p>
 * <b>Up</b>: the main source must not quietly grow new unkeyed global state. A mutable
 * {@code static} that is not a collection is global by construction: there is no key, so there
 * is nothing to scope it by, and any two callers in flight share it. That is exactly the shape
 * of {@code XraySweep.lastTiming}, which held the most recent sweep's measurement in a static,
 * was read by one gametest, and gave a read-after-write race between any two sweeps — so a test
 * could measure another test's work and pass for the wrong reason.
 * <p>
 * So every mutable static in the mod is enumerated below with what it is. Adding one fails this
 * test until somebody classifies it, in the same way {@code ActorBoundaryTest} fails when
 * {@code Actor} gains a component. The point is not that the list is interesting; it is that
 * the addition is a decision rather than a side effect.
 */
class SharedStateTest {

	private static final Path MAIN = Path.of("src", "main", "java");
	private static final Path GAMETESTS = Path.of("src", "gametest", "java");

	private static Stream<Path> javaUnder(Path root) throws IOException {
		return Files.walk(root).filter(p -> p.toString().endsWith(".java"));
	}

	// ------------------------------------------------------------------- down

	/** Writing a field on the config singleton. Every concurrent test sees it. */
	private static final Pattern CONFIG_WRITE =
			Pattern.compile("StaffConfig\\.get\\(\\)\\.\\w+\\s*=[^=]");

	/** A reset that clears state for every player rather than one. */
	private static final Pattern GLOBAL_RESET =
			Pattern.compile("\\.\\s*(?:forgetAll|clearAll|resetAll)\\s*\\(");

	@Test
	@DisplayName("no gametest writes to the config singleton")
	void gametestsDoNotMutateSharedConfig() throws IOException {
		List<String> offenders = scan(GAMETESTS, CONFIG_WRITE);

		assertTrue(offenders.isEmpty(),
				"a gametest writes to StaffConfig, which every other test running at the same "
						+ "time reads:\n  " + String.join("\n  ", offenders)
						+ "\n\nRestoring it in a finally does not help — the window is the "
						+ "whole test, and the tests overlap. Assert on something scoped to "
						+ "this test's own player or position instead, so the real "
						+ "configuration can stay as it is.");
	}

	@Test
	@DisplayName("no gametest calls a global reset")
	void gametestsDoNotResetGlobalState() throws IOException {
		List<String> offenders = scan(GAMETESTS, GLOBAL_RESET);

		assertTrue(offenders.isEmpty(),
				"a gametest calls a global reset, which clears state for every player while "
						+ "other tests are mid-assertion:\n  " + String.join("\n  ", offenders)
						+ "\n\nIsolation is already free: every mock player has its own UUID, "
						+ "this state is keyed by it, and leftover state for a player nobody "
						+ "will ask about again costs nothing in a server that is about to "
						+ "exit. If a test genuinely needs a clean global slate it needs its "
						+ "own environment, not a reset.");
	}

	@Test
	@DisplayName("the scans would catch a real offender, so a pass means something")
	void theScansAreNotVacuous() {
		// Both tests above pass by finding nothing, which is the exact shape this project has
		// been caught by repeatedly. Checking the checker is not optional here.
		assertTrue(CONFIG_WRITE.matcher("StaffConfig.get().canaryDensity = 1;").find());
		assertTrue(CONFIG_WRITE.matcher("\t\tStaffConfig.get().positionTracking = true;").find());
		assertTrue(GLOBAL_RESET.matcher("Canaries.forgetAll();").find());
		assertTrue(GLOBAL_RESET.matcher("BlockIllusions.forgetAll();").find());

		// And would not fire on the things that are fine, or the tests become unusable and
		// somebody deletes them.
		assertTrue(!CONFIG_WRITE.matcher("if (StaffConfig.get().canaryDensity == 1) {").find(),
				"reading a config value was flagged as writing one");
		assertTrue(!GLOBAL_RESET.matcher("Canaries.forget(player.getUUID());").find(),
				"a per-player forget was flagged as a global reset, which would push people "
						+ "towards the global one");
	}

	// --------------------------------------------------------------------- up

	/**
	 * A {@code static} field that is not {@code final}.
	 * <p>
	 * Deliberately textual. Anything cleverer would need the classes loaded, and a test that
	 * needs the thing it is checking to already work is not much of a check.
	 */
	private static final Pattern MUTABLE_STATIC = Pattern.compile(
			"^\\s*(?:public|private|protected)?\\s*static\\s+(?!final\\b)"
					+ "(?:volatile\\s+)?(?!final\\b)[A-Za-z_][\\w.<>\\[\\], ]*?\\s+(\\w+)\\s*(?:=|;)");

	/**
	 * Every mutable static in the mod, and why it is allowed to be one.
	 * <p>
	 * Almost all of these are written once at boot or are idempotent caches — a second writer
	 * computes the same answer, so a race between them is invisible. Those are safe to share
	 * across concurrent tests. The category that is <em>not</em> safe is a value that changes
	 * over the life of the server and that somebody reads back expecting their own write:
	 * there is exactly one of those left, the config singleton, and the tests are forbidden
	 * from writing to it above.
	 */
	private static final Set<String> DECLARED = new LinkedHashSet<>(List.of(
			// The config singleton. Mutable by design — /staff reload replaces it — and the
			// one thing here a test could realistically corrupt for its neighbours.
			"StaffConfig.instance",

			// Written once, at boot, before anything reads them.
			"StartupCheck.disabled",
			"StartupCheck.report",
			"StaffCore.server",
			"StaffCore.startedAt",
			"StaffCore.brokenFeatures",
			"StaffToolset.registered",
			"PositionSampler.worker",

			// Idempotent caches: computed on first use, and a racing second computation
			// produces the same value.
			"AddressPrivacy.salt",
			"AntiXrayCompanion.installed",
			"Excavation.targetBlocks",
			"IllegalItems.spawnEggs",
			"Actor.allNodes",
			"PermissionGroups.instance"));

	@Test
	@DisplayName("every mutable static is one somebody decided to add")
	void noUndeclaredGlobalState() throws IOException {
		List<String> found = new ArrayList<>();

		for (Path file : javaUnder(MAIN).toList()) {
			String type = file.getFileName().toString().replace(".java", "");
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.contains("static final")) continue;

				Matcher m = MUTABLE_STATIC.matcher(line);
				// A method declaration can look like a field up to the parenthesis.
				if (m.find() && !line.substring(0, line.indexOf(m.group(1))).contains("(")) {
					found.add(type + "." + m.group(1));
				}
			}
		}

		List<String> undeclared = found.stream().filter(f -> !DECLARED.contains(f)).sorted()
				.distinct().toList();

		assertTrue(undeclared.isEmpty(),
				"new mutable static field(s) with nothing saying what they are:\n  "
						+ String.join("\n  ", undeclared)
						+ "\n\nA mutable static that is not a keyed collection is global by "
						+ "construction — there is no key, so there is nothing to scope it "
						+ "by, and any two callers in flight share it. XraySweep.lastTiming "
						+ "was one of these: it held the last sweep's measurement, one "
						+ "gametest read it back, and two concurrent sweeps meant a test "
						+ "could measure another test's work and pass for the wrong reason.\n\n"
						+ "If it is written once at boot or is an idempotent cache, add it to "
						+ "DECLARED under the right heading. If it changes over the life of "
						+ "the server and somebody reads back their own write, it needs a key "
						+ "instead.");

		List<String> stale = DECLARED.stream().filter(d -> !found.contains(d)).toList();
		assertTrue(stale.isEmpty(),
				"DECLARED lists field(s) that no longer exist: " + stale
						+ ". A stale allowlist entry silently permits a future field of the "
						+ "same name.");
	}

	@Test
	@DisplayName("the static scan finds real fields, so its silence would mean something")
	void theStaticScanIsNotVacuous() {
		assertTrue(MUTABLE_STATIC.matcher("\tprivate static volatile Timing lastTiming = null;")
				.find(), "a volatile mutable static was not recognised");
		assertTrue(MUTABLE_STATIC.matcher("\tprivate static StaffConfig instance;").find());

		assertTrue(!MUTABLE_STATIC.matcher("\tprivate static final int LIMIT = 5;").find(),
				"a constant was flagged as mutable state, which would make this list useless");

		// And it actually found things in the real source — a regex that matched nothing would
		// make noUndeclaredGlobalState pass forever while the codebase filled up with globals.
		assertEquals(14, DECLARED.size(),
				"the declared list changed size; check the new entry is classified correctly "
						+ "rather than added to make the build green");
	}

	// ------------------------------------------------------------------ shared

	private static List<String> scan(Path root, Pattern pattern) throws IOException {
		List<String> out = new ArrayList<>();

		for (Path file : javaUnder(root).toList()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				// A line that only talks about the pattern is documentation, not a call.
				String code = line.strip();
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
					continue;
				}
				if (pattern.matcher(line).find()) {
					out.add(file.getFileName() + ":" + (i + 1) + "  " + code);
				}
			}
		}
		return out;
	}
}
