package io.github.alphain24.staffcore.diagnostic;

import io.github.alphain24.staffcore.StaffCore;
import io.github.alphain24.staffcore.compat.Mc;
import io.github.alphain24.staffcore.config.StaffConfig;
import io.github.alphain24.staffcore.module.Mods;
import io.github.alphain24.staffcore.modules.security.IllegalItems;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the parts of the mod that only exist once a real server is running.
 * <p>
 * The health check answers "did our hooks attach". That is necessary and nowhere near
 * sufficient: a hook can attach perfectly to a feature whose query is malformed, whose table
 * is missing a column, or whose command was never registered. Every one of those compiles,
 * starts cleanly, passes the health check, and fails the first time somebody uses it.
 * <p>
 * So this uses the mod. It writes a backup, exports the database, compiles the contraband
 * list against the real registry, walks the command tree and scores a mining session —
 * touching the storage layer, the registries and Brigadier in the process. Read-only where it
 * can be, and where it cannot, it only writes files the mod already writes on its own.
 * <p>
 * Run it with {@code /staff selftest}, or automatically at boot with
 * {@code -Dstaffcore.selftest=true}, which is how continuous integration drives it.
 */
public final class SelfTest {
	private SelfTest() {}

	/** Set this property to run the whole thing once the server has finished starting. */
	public static final String PROPERTY = "staffcore.selftest";

	/** One check and how it went. */
	public record Result(String name, boolean passed, String detail) {

		/** ASCII only: this goes to the server console, which is rarely UTF-8. */
		public String line() {
			return (passed ? "  PASS  " : "  FAIL  ") + name + " - " + detail;
		}
	}

	/** True when the JVM was told to self-test at boot. */
	public static boolean requestedAtBoot() {
		return Boolean.getBoolean(PROPERTY);
	}

	/**
	 * Runs every check. Never throws: a self-test that takes the server down with it would
	 * be a worse failure than whatever it was looking for.
	 */
	public static List<Result> run(MinecraftServer server) {
		List<Result> results = new ArrayList<>();

		results.add(check("storage", () -> {
			if (!StaffCore.storage().isReady()) return "the database is not open";
			try (var st = StaffCore.storage().conn().createStatement();
					var rs = st.executeQuery("PRAGMA user_version")) {
				int version = rs.next() ? rs.getInt(1) : 0;
				return version > 0
						? "open, schema v" + version
						: "schema version is 0 — migrations never stamped it";
			}
		}, detail -> detail.startsWith("open,")));

		results.add(check("modules", () -> {
			return StaffCore.modules().count() + " enabled";
		}, detail -> !detail.startsWith("0 ")));

		results.add(check("commands", () -> {
			var root = server.getCommands().getDispatcher().getRoot().getChild("staff");
			if (root == null) return "the /staff tree is not registered";
			return root.getChildren().size() + " subcommands registered";
		}, detail -> !detail.contains("not registered")));

		results.add(check("contraband", () -> {
			IllegalItems.Ruleset rules = Mods.security().illegalRules();
			if (!rules.unknown().isEmpty()) {
				return "entries matching no item: " + String.join(", ", rules.unknown());
			}
			return rules.size() + " items covered, including " + IllegalItems.spawnEggCount()
					+ " spawn eggs";
		}, detail -> !detail.startsWith("entries matching")));

		results.add(check("xray scoring", () -> {
			// A synthetic session the detector must have an opinion about. This is the whole
			// scoring path, run against the real config thresholds.
			var breaks = new ArrayList<io.github.alphain24.staffcore.modules.security.XrayDetector.Break>();
			for (int i = 0; i < 60; i++) {
				breaks.add(new io.github.alphain24.staffcore.modules.security.XrayDetector.Break(
						i % 2 == 0 ? "minecraft:deepslate_diamond_ore" : "minecraft:deepslate",
						i, 12, 0, i * 1000L));
			}
			int score = io.github.alphain24.staffcore.modules.security.XrayDetector.score(breaks, 0)
					.confidence();
			return "obvious session scored " + score + "%";
		}, detail -> !detail.contains(" 0%")));

		results.add(check("grief query", Mods.grief()::areaQuerySelfCheck,
				detail -> detail.startsWith("wrote a break")));

		results.add(check("explosion log", Mods.grief()::explosionSelfCheck,
				detail -> detail.startsWith("recorded explosion")));

		results.add(check("pickup log", Mods.grief()::pickupSelfCheck,
				detail -> detail.startsWith("recorded a pickup")));

		results.add(check("backup", () -> {
			Path written = StaffCore.storage().backup("self test");
			if (written == null) return "no file was written";
			return Files.exists(written) ? written.getFileName().toString() : "file is missing";
		}, detail -> !detail.contains("no file") && !detail.contains("missing")));

		results.add(check("export", () -> {
			Path dir = StaffCore.storage().export();
			if (dir == null) return "no directory was written";
			try (var files = Files.list(dir)) {
				long csv = files.filter(f -> f.toString().endsWith(".csv")).count();
				return csv + " CSV file(s)";
			}
		}, detail -> !detail.startsWith("0 ") && !detail.contains("no directory")));

		results.add(check("config", () -> {
			StaffConfig cfg = StaffConfig.get();
			return "v" + cfg.configVersion + ", staff mode gamemode " + cfg.staffModeGameMode;
		}, detail -> true));

		return results;
	}

	/** Runs the checks and writes the outcome to the log, for CI to read. */
	public static boolean runAndLog(MinecraftServer server) {
		List<Result> results = run(server);
		long failed = results.stream().filter(r -> !r.passed()).count();

		StaffCore.LOGGER.info("[StaffCore] Self test: {}/{} checks passed.",
				results.size() - failed, results.size());
		for (Result result : results) {
			if (result.passed()) StaffCore.LOGGER.info("[StaffCore]{}", result.line());
			else StaffCore.LOGGER.error("[StaffCore]{}", result.line());
		}
		return failed == 0;
	}

	// ------------------------------------------------------------------- plumbing

	@FunctionalInterface
	private interface Probe {
		String measure() throws Exception;
	}

	/**
	 * Runs one probe and decides whether what it reported counts as a pass.
	 * <p>
	 * The probe returns a description rather than a boolean so the log says what was actually
	 * found, not just whether somebody approved of it. A check that fails with "FAIL storage"
	 * sends you looking; one that fails with "FAIL storage — schema version is 0" tells you
	 * where.
	 */
	private static Result check(String name, Probe probe, java.util.function.Predicate<String> passes) {
		try {
			String detail = probe.measure();
			return new Result(name, passes.test(detail), detail);
		} catch (Exception | LinkageError e) {
			return new Result(name, false, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}
