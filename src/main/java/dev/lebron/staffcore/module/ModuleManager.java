package dev.lebron.staffcore.module;

import dev.lebron.staffcore.StaffCore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ModuleManager {
	private final Map<String, Module> registered = new LinkedHashMap<>();
	private boolean enabled;

	public void register(Module module) {
		registered.put(module.id(), module);
		if (enabled) {
			safely(module, true);
		}
	}

	public void enableAll() {
		if (enabled) return;
		enabled = true;
		registered.values().forEach(m -> safely(m, true));
	}

	public void disableAll() {
		if (!enabled) return;
		enabled = false;
		registered.values().forEach(m -> safely(m, false));
	}

	private void safely(Module module, boolean enable) {
		try {
			if (enable) module.onEnable();
			else module.onDisable();
		} catch (Exception e) {
			StaffCore.LOGGER.error("[StaffCore] Module '{}' failed to {}", module.id(),
					enable ? "enable" : "disable", e);
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Module> Optional<T> get(String id, Class<T> type) {
		Module m = registered.get(id);
		return (m != null && type.isInstance(m)) ? Optional.of((T) m) : Optional.empty();
	}

	/**
	 * Same as {@link #get} but throws when absent. Use this from GUI/command code where
	 * a missing core module is a programming error, not a runtime condition.
	 */
	public <T extends Module> T require(String id, Class<T> type) {
		return get(id, type).orElseThrow(() ->
				new IllegalStateException("StaffCore module '" + id + "' is not registered"));
	}

	public Collection<Module> all() {
		return registered.values();
	}

	public int count() {
		return registered.size();
	}
}
