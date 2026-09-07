package io.github.alphain24.staffcore.modules.cases;

import net.fabricmc.loader.api.FabricLoader;

/**
 * What was running when a record was written.
 * <p>
 * Every case, signal and audit row carries these. It looks like bookkeeping until the first
 * time somebody asks why a detector behaved differently in March — at which point "which
 * version produced this row" is the difference between an answer and a shrug. Detection
 * thresholds move, mixins stop applying across Minecraft updates, and a row written under a
 * build where a hook was silently broken means something different from one written under a
 * healthy build.
 * <p>
 * Read once and cached: neither can change without a restart, and both are looked up on a
 * path that runs per signal.
 */
public final class Versions {
	private Versions() {}

	private static final String MOD = read("staffcore", "unknown");
	private static final String MINECRAFT = readMinecraft();

	/** StaffCore's own version, e.g. {@code 1.1.0}. */
	public static String mod() {
		return MOD;
	}

	/** The Minecraft version, e.g. {@code 26.2}. */
	public static String minecraft() {
		return MINECRAFT;
	}

	private static String read(String modId, String fallback) {
		try {
			return FabricLoader.getInstance().getModContainer(modId)
					.map(c -> c.getMetadata().getVersion().getFriendlyString())
					.orElse(fallback);
		} catch (RuntimeException e) {
			// A version string is never worth failing a write over.
			return fallback;
		}
	}

	private static String readMinecraft() {
		try {
			return net.minecraft.SharedConstants.getCurrentVersion().name();
		} catch (RuntimeException | LinkageError e) {
			return read("minecraft", "unknown");
		}
	}
}
