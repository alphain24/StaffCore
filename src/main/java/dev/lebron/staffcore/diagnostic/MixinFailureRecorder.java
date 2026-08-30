package dev.lebron.staffcore.diagnostic;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mixin telling us, in its own words, which of our mixins did not apply.
 * <p>
 * {@link StartupCheck} used to infer this, and inference turned out to be wrong twice over.
 * It first looked for the handler method under its original name — Mixin renames merged
 * handlers, so every working mixin reported as broken. Corrected to a looser match, it then
 * reported two genuinely broken mixins as <em>fine</em>, because Mixin merges a handler
 * method into the target class <em>before</em> it wires the injection up: when the wiring
 * fails, the orphaned method is still sitting there for a reflective search to find. A
 * present handler proves the mixin was processed, not that it took effect.
 * <p>
 * There is no reflective signal that distinguishes those two cases, because the difference
 * lives in the target method's bytecode. So stop guessing and ask: {@code onApplyError} is
 * a supported extension point and fires with the exact mixin that failed and why. Registered
 * from {@link MixinDiagnostics} during {@code preLaunch}, which is the last moment before
 * mixins start being applied.
 * <p>
 * The handler is deliberately passive. It records and returns the action Mixin already
 * chose, so a non-fatal mixin stays non-fatal and nothing here can turn a cosmetic failure
 * into a refusal to boot.
 */
public final class MixinFailureRecorder implements IMixinErrorHandler {

	/** Mixin class name to a short reason, in the order the failures happened. */
	private static final Map<String, String> FAILURES = new LinkedHashMap<>();

	private static final String OURS = "dev.lebron.staffcore.mixin.";

	@Override
	public ErrorAction onPrepareError(IMixinConfig config, Throwable cause, IMixinInfo mixin,
			ErrorAction action) {
		record(mixin, cause);
		return action;
	}

	@Override
	public ErrorAction onApplyError(String targetClassName, Throwable cause, IMixinInfo mixin,
			ErrorAction action) {
		record(mixin, cause);
		return action;
	}

	private static void record(IMixinInfo mixin, Throwable cause) {
		if (mixin == null) return;

		String name = mixin.getClassName();
		// Somebody else's broken mixin is not our diagnostic to report, and claiming it
		// would send an admin looking for a StaffCore bug that is not there.
		if (name == null || !name.startsWith(OURS)) return;

		FAILURES.putIfAbsent(name, summarise(cause));
	}

	/**
	 * The useful line out of a Mixin stack trace.
	 * <p>
	 * These messages are long and repeat the mixin name three times; an admin needs the
	 * shape of the problem, and the full trace is already in the log above.
	 */
	private static String summarise(Throwable cause) {
		if (cause == null) return "unknown reason";

		String message = cause.getMessage();
		if (message == null || message.isBlank()) return cause.getClass().getSimpleName();

		int detail = message.indexOf(". ");
		String first = detail > 0 ? message.substring(0, detail) : message;
		return first.length() > 160 ? first.substring(0, 157) + "…" : first;
	}

	/** Every StaffCore mixin that failed, class name to reason. Empty on a healthy server. */
	public static Map<String, String> failures() {
		return Map.copyOf(FAILURES);
	}

	/** Whether this specific mixin class failed to apply. */
	public static boolean failed(String mixinClassName) {
		return FAILURES.containsKey(mixinClassName);
	}

	public static String reasonFor(String mixinClassName) {
		return FAILURES.get(mixinClassName);
	}
}
