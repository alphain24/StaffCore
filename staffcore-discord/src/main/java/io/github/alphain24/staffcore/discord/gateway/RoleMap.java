package io.github.alphain24.staffcore.discord.gateway;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discord role ids to the StaffCore nodes the settings say they may use.
 * <p>
 * The companion's half of the permission question, and the smaller half: StaffCore narrows this
 * again to what the linked account holds in game, so nothing here can grant on its own.
 */
public final class RoleMap {

	private final Map<String, List<String>> nodesByRole;

	/** @param nodesByRole the validated mapping, from the settings */
	public RoleMap(Map<String, List<String>> nodesByRole) {
		this.nodesByRole = Map.copyOf(nodesByRole);
	}

	/** Every node any of these roles maps to. Roles that are not mapped add nothing. */
	public Set<String> nodesFor(Collection<String> roleIds) {
		Set<String> nodes = new LinkedHashSet<>();
		if (roleIds == null) return nodes;
		for (String role : roleIds) {
			List<String> mapped = nodesByRole.get(role);
			if (mapped != null) nodes.addAll(mapped);
		}
		return nodes;
	}
}
