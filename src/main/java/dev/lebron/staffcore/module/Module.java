package dev.lebron.staffcore.module;

/**
 * A pluggable unit of staff functionality. Core modules and addons both implement
 * this. Keep {@link #onEnable()} and {@link #onDisable()} idempotent so a reload is safe.
 */
public interface Module {
	String id();

	/** Human-readable name shown in the GUI. */
	default String displayName() {
		return id();
	}

	default void onEnable() {}

	default void onDisable() {}
}
