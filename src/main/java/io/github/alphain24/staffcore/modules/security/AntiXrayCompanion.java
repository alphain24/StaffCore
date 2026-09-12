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
 *
 * <h2>Two reasons were originally given, and both were wrong</h2>
 * Recorded here because the wrong reasons are more instructive than the right one, and because
 * this is the second time a confident explanation on this feature turned out to be a story.
 * <p>
 * <b>"Both mods rewrite the same outbound chunk data."</b> False. StaffCore does not touch
 * chunk serialisation at all — a canary is a {@code ClientboundBlockUpdatePacket} sent
 * <em>after</em> the chunk arrives, so it lands on top of whatever that chunk contained,
 * obfuscated or not. Since the chunk-send hook it is re-asserted after every resend as well,
 * which if anything makes the two <em>more</em> likely to coexist rather than less.
 * <p>
 * <b>"A player breaking a fake block cannot be attributed."</b> Weak. We know our own canary
 * positions exactly and never need to identify anybody else's.
 *
 * <h2>The reason that actually holds</h2>
 * It is not about packets, it is about the player. A bulk anti-xray in engine mode 2 fills the
 * world with fabricated ore, so somebody using an x-ray pack is already looking at a screen
 * full of ore that is not there. Within an hour they learn that nothing the pack shows them is
 * real and stop digging to any of it.
 * <p>
 * That does not make a canary hit harder to interpret. <b>It removes the true positives.</b>
 * Nobody walks to a decoy because nobody walks to anything. The feature would report a clean
 * zero and the zero would mean nothing — which is the same shape as the false-positive rate
 * that was measured before anybody had confirmed decoys reach a client at all.
 * <p>
 * So canaries switch off when one is detected, and the startup report says why. The rest of the
 * x-ray detection works from the block log after the fact and is unaffected.
 *
 * <h2>What is still unmeasured</h2>
 * Whether the two mods' block updates fight when they write the same position — ours
 * asserting a decoy, theirs deobfuscating around an approaching player. Ordering there decides
 * who wins and cannot be answered from this side. {@code canaryForceWithBulkAntiXray} exists to
 * answer it; {@code docs/manual-checks/antixray-compat.md} is the script.
 */
public final class AntiXrayCompanion {
	private AntiXrayCompanion() {}

	/**
	 * Anti-xray mods known to obfuscate chunk data before it is sent, by mod id.
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
		return "Canaries are off because " + names + " is installed. That mod already fills the "
				+ "world with ore that is not there, so an x-ray user learns within an hour that "
				+ "nothing their pack shows them is real and stops digging to any of it — which "
				+ "costs the decoys their true positives, not just the reading of them. Set "
				+ "canaryForceWithBulkAntiXray if you are testing how the two interact; signals "
				+ "produced that way are not defensible in an appeal.";
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
