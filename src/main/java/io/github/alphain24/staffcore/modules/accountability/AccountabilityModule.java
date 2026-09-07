package io.github.alphain24.staffcore.modules.accountability;

import io.github.alphain24.staffcore.module.Module;

/**
 * Watching the watchers.
 * <p>
 * Every other module in this mod is aimed at players. This one is aimed at staff, and it
 * exists because the same tools that make a good moderator effective make a compromised
 * account devastating: the account that can ban forty people in a minute is by definition a
 * staff account, and nothing else here would notice.
 * <p>
 * Three parts, and they answer three different questions. The audit says <em>what</em> a staff
 * member did. The rate limits bound how fast anybody can do it. The approvals require a second
 * person for the three actions that cannot be undone by apologising.
 */
public class AccountabilityModule implements Module {

	@Override
	public String id() {
		return "accountability";
	}

	@Override
	public String displayName() {
		return "Accountability";
	}

	private final StaffAudit audit = new StaffAudit();
	private final RateLimits limits = new RateLimits();
	private final Approvals approvals = new Approvals();

	public StaffAudit audit() {
		return audit;
	}

	public RateLimits limits() {
		return limits;
	}

	public Approvals approvals() {
		return approvals;
	}
}
