package io.github.alphain24.staffcore.api;

/**
 * Told about everything in {@link StaffCoreEvent}.
 * <p>
 * Called on StaffCore's own event thread, never the server thread, one event at a time and in
 * the order they happened. Return quickly: a listener that does its I/O here holds up every
 * listener after it. Nothing a listener does or throws can reach the action that raised the
 * event — the punishment is already written before anybody is told about it.
 */
@FunctionalInterface
public interface StaffCoreListener {
	void onEvent(StaffCoreEvent event);
}
