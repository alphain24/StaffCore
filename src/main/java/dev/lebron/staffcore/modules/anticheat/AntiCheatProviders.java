package dev.lebron.staffcore.modules.anticheat;

import dev.lebron.staffcore.StaffCore;
import dev.lebron.staffcore.config.StaffConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds anti-cheats that are installed and wires them into {@link AntiCheatModule}.
 * <p>
 * StaffCore cannot compile against an anti-cheat it does not ship with, and demanding one as
 * a hard dependency would mean the mod refuses to start without it. So attachment is done at
 * runtime, by name, and every failure is a log line rather than an exception: a provider that
 * is absent, or present but changed since this was written, costs the bridge and nothing else.
 * <p>
 * <b>The reliable route is the public API.</b> Any mod, script or provider can call
 * {@link AntiCheatModule#report(AntiCheatEvent)} directly, and that path needs no reflection,
 * no version guessing and no cooperation from this class. The adapters below are a
 * convenience for providers that have not been asked to integrate; the API is the contract.
 */
final class AntiCheatProviders {
	private AntiCheatProviders() {}

	/**
	 * One anti-cheat this class knows how to look for.
	 *
	 * @param modId       what the provider calls itself to Fabric
	 * @param label       what staff should see it called
	 * @param eventClass  the class carrying its detection events, if it has one we can find
	 */
	private record Known(String modId, String label, String eventClass) {}

	/**
	 * Providers worth looking for.
	 * <p>
	 * Presence detection is exact and always works — Fabric knows what is loaded. Binding to
	 * a provider's events is best-effort, and the honest summary is that it depends entirely
	 * on that provider keeping its own class names stable, which is not a promise anybody has
	 * made to us.
	 */
	private static final List<Known> KNOWN = List.of(
			new Known("polar", "Polar", "com.peaches.polar.api.PolarAPI"),
			new Known("grim", "Grim", "ac.grim.grimac.api.GrimAbstractAPI"),
			new Known("vulcan", "Vulcan", null),
			new Known("matrix", "Matrix", null));

	/**
	 * Attaches to everything present, returning the names of what actually wired up.
	 * <p>
	 * A provider that is installed but could not be bound is reported loudly and separately
	 * from one that is simply absent, because those need opposite responses from an admin:
	 * one is "install it if you want it", the other is "this stopped working and you should
	 * know before you rely on it".
	 */
	static List<String> attachAll(AntiCheatModule module) {
		List<String> attached = new ArrayList<>();
		if (!StaffConfig.get().antiCheatBridge) {
			StaffCore.LOGGER.info("[AntiCheat] Bridge is off (antiCheatBridge=false)");
			return attached;
		}

		List<String> presentButUnbound = new ArrayList<>();
		for (Known provider : KNOWN) {
			if (!FabricLoader.getInstance().isModLoaded(provider.modId())) continue;

			if (bind(module, provider)) attached.add(provider.label());
			else presentButUnbound.add(provider.label());
		}

		if (attached.isEmpty() && presentButUnbound.isEmpty()) {
			StaffCore.LOGGER.info("[AntiCheat] No supported anti-cheat found. Findings can still "
					+ "be pushed in through the public API - see the handbook.");
		}
		if (!attached.isEmpty()) {
			StaffCore.LOGGER.info("[AntiCheat] Bridged: {}", String.join(", ", attached));
		}
		for (String unbound : presentButUnbound) {
			StaffCore.LOGGER.warn("[AntiCheat] {} is installed but StaffCore could not attach to "
					+ "its events. Its findings will NOT appear in staff alerts unless it calls "
					+ "the StaffCore API itself. This usually means the provider changed its API.",
					unbound);
		}
		return attached;
	}

	/**
	 * Tries to reach a provider's event surface.
	 * <p>
	 * Only presence is confirmed here. Subscribing to another mod's events means calling
	 * methods this code cannot see at compile time, and inventing those method names would
	 * produce something that compiles, runs, catches its own failure and silently does
	 * nothing — which is worse than not trying, because it looks like it worked.
	 * <p>
	 * So the class is probed and the result reported honestly. Where a provider is found, the
	 * admin is told exactly what to do about it rather than left assuming it is handled.
	 */
	private static boolean bind(AntiCheatModule module, Known provider) {
		if (provider.eventClass() == null) return false;

		try {
			Class.forName(provider.eventClass(), false,
					AntiCheatProviders.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError missing) {
			return false;
		}

		StaffCore.LOGGER.info("[AntiCheat] {} detected. StaffCore will accept its findings "
				+ "through the public API - point its alert hook at "
				+ "StaffCore.antiCheat().report(...). See the handbook for a worked example.",
				provider.label());

		// The class is there, which is all this can honestly confirm. Reporting it as
		// attached would claim a subscription that does not exist.
		return false;
	}
}
