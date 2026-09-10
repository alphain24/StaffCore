package io.github.alphain24.staffcore.modules.security;

import io.github.alphain24.staffcore.StaffCore;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a real anti-xray is installed alongside this mod, and what that means for canaries.
 *
 * <h2>Why StaffCore does not do bulk obfuscation</h2>
 * Hiding ores from a cheating client means rewriting the block palette of every chunk on its
 * way out, per player, without costing the server its tick budget. That is a heavy,
 * performance-sensitive piece of work that already exists, is already maintained, and already
 * targets this Minecraft version. Writing a second one would produce a worse copy that this
 * project could not credibly keep current.
 * <p>
 * The licences were checked before a line was written, because vendoring a GPL implementation
 * would have made StaffCore GPL. Both are MIT — {@code DrexHD/AntiXray}, "Copyright (c) 2021
 * Drex", and {@code xiaoyiluck666/MeowAnti-Xray}, "Copyright (c) 2026 xiaoyiluck". So the
 * answer was permissive and the decision did not turn on it: not vendoring is an engineering
 * choice, and finding out the licence would have allowed it does not change the reasoning.
 *
 * <h2>Why an installed one turns canaries off</h2>
 * A canary is a fake block at a known position, sent to one player, that nothing but an x-ray
 * client would go straight to. A bulk anti-xray fills the same chunks with fake blocks by the
 * thousand and handles the resync itself.
 * <p>
 * Two problems, and the second is the one that matters. Both mods rewrite the same outbound
 * chunk data, so there is no guarantee a canary survives to the client at all — whichever runs
 * second wins, and neither knows about the other. And a player breaking into a fake block
 * cannot be attributed: theirs and ours produce the same event, and the overwhelming majority
 * of fakes are theirs. A signal that cannot say whose block it was is a signal that reads as
 * evidence and is not.
 * <p>
 * So canaries switch off when one is detected, and the startup report says why. That is a
 * worse outcome than making them work together and a much better one than emitting signals
 * nobody can interpret — the failure it avoids is a case opened on a player who walked into
 * somebody else's decoy.
 */
public final class AntiXrayCompanion {
	private AntiXrayCompanion() {}

	/**
	 * Anti-xray mods known to rewrite outbound chunk data, by mod id.
	 * <p>
	 * By id rather than by behaviour, because there is no way to ask a mod whether it
	 * obfuscates chunks. That makes this list something somebody has to maintain, and an
	 * unknown mod doing the same thing is invisible to it — named in Known limits rather than
	 * pretended away.
	 */
	private static final Map<String, String> KNOWN = new LinkedHashMap<>(Map.of(
			"antixray", "AntiXray (DrexHD) — Paper's 0367-Anti-Xray patch, ported",
			"meowantixray", "Meow Anti-Xray — Paper-like obfuscation, engine mode 2"));

	/** What was found, resolved once at startup. */
	public record Found(String modId, String description, String version) {}

	private static List<Found> installed;

	/**
	 * Anti-xray mods present in this instance.
	 * <p>
	 * Cached: the mod list does not change while the server is running, and this is read from
	 * the startup report, the diagnostic screen and the canary gate.
	 */
	public static synchronized List<Found> installed() {
		if (installed != null) return installed;

		List<Found> found = new ArrayList<>();
		for (Map.Entry<String, String> candidate : KNOWN.entrySet()) {
			Optional<net.fabricmc.loader.api.ModContainer> mod =
					FabricLoader.getInstance().getModContainer(candidate.getKey());

			mod.ifPresent(container -> found.add(new Found(candidate.getKey(), candidate.getValue(),
					container.getMetadata().getVersion().getFriendlyString())));
		}
		installed = List.copyOf(found);
		return installed;
	}

	/** True when something else is already rewriting chunk data. */
	public static boolean present() {
		return !installed().isEmpty();
	}

	/**
	 * Why canaries are off, or null when they are not.
	 * <p>
	 * A sentence rather than a boolean, because "off" with no reason attached is the thing an
	 * admin reads as broken and reports as a bug.
	 */
	public static String whyCanariesAreOff() {
		List<Found> found = installed();
		if (found.isEmpty()) return null;

		String names = String.join(" and ", found.stream().map(Found::modId).toList());
		return "Canaries are off because " + names + " is installed. Both rewrite the same "
				+ "outbound chunk data, so a canary is not guaranteed to reach the client — and "
				+ "a player breaking a fake block cannot be told apart from one breaking theirs. "
				+ "A signal nobody can attribute reads as evidence and is not.";
	}

	/** The line the startup report prints, whichever way it went. */
	public static String startupLine() {
		List<Found> found = installed();
		if (found.isEmpty()) {
			return "Anti-xray: none installed. StaffCore detects x-ray after the fact and does "
					+ "not prevent it — an obfuscating mod alongside this one is the half that "
					+ "stops it happening.";
		}

		StringBuilder out = new StringBuilder("Anti-xray: ");
		for (int i = 0; i < found.size(); i++) {
			if (i > 0) out.append(", ");
			out.append(found.get(i).description())
					.append(" v").append(found.get(i).version());
		}
		return out.toString();
	}

	/** Only for tests, which need to ask what the code would do with a given mod list. */
	static synchronized void forget() {
		installed = null;
	}

	/** Every mod id this knows to look for. Read by the docs test, so the two cannot drift. */
	public static java.util.Set<String> knownIds() {
		return KNOWN.keySet();
	}

	public static void log() {
		StaffCore.LOGGER.info("[StaffCore] {}", startupLine());

		// Loud, every boot, and it names the consequence rather than the setting. A server
		// that leaves this on by accident produces canary signals that read exactly like
		// ordinary ones and mean something entirely different.
		if (Canaries.inUnsupportedMode()) {
			StaffCore.LOGGER.warn("[StaffCore] canaryForceWithBulkAntiXray is ON and a bulk "
					+ "anti-xray is installed. Decoys are being placed anyway. This is a "
					+ "compatibility-testing mode, not a supported one: with fabricated ore "
					+ "everywhere an x-ray user stops trusting any of it, so decoys lose their "
					+ "true positives rather than just their interpretation. CANARY SIGNALS "
					+ "PRODUCED IN THIS MODE ARE NOT DEFENSIBLE IN AN APPEAL. Set it back to "
					+ "false unless you are deliberately testing the interaction.");
		}

		String off = whyCanariesAreOff();
		if (off != null) StaffCore.LOGGER.info("[StaffCore] {}", off);
	}
}
