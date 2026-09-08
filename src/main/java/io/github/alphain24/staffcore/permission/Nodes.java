package io.github.alphain24.staffcore.permission;

/** Single source of truth for every permission node StaffCore checks. */
public final class Nodes {
	private Nodes() {}

	// core
	public static final String STAFF_GUI       = "staff.gui";
	public static final String STAFF_MODE      = "staff.mode";
	public static final String VANISH          = "staff.vanish";
	public static final String FREEZE          = "staff.freeze";
	public static final String TP              = "staff.tp";
	public static final String TP_HERE         = "staff.tphere";
	public static final String TP_POS          = "staff.tppos";

	// punishments
	public static final String PUNISH          = "staff.punish";
	public static final String BAN             = "staff.punish.ban";
	public static final String MUTE            = "staff.punish.mute";
	public static final String KICK            = "staff.punish.kick";
	public static final String WARN            = "staff.punish.warn";
	public static final String UNPUNISH        = "staff.punish.revoke";
	public static final String HISTORY         = "staff.history";
	public static final String HISTORY_CLEAR   = "staff.history.clear";

	// notes / chat / alerts
	public static final String NOTES           = "staff.notes";
	public static final String NOTES_VIEW      = "staff.notes.view";
	public static final String NOTES_REMOVE    = "staff.notes.remove";
	public static final String CHAT            = "staff.chat";
	public static final String ALERTS          = "staff.alerts";
	public static final String RELOAD          = "staff.reload";

	// reports
	public static final String REPORT_USE      = "report.use";   // open to everyone
	public static final String REPORT_VIEW     = "report.view";

	// identity & logs
	public static final String ALTS            = "staff.alts";
	public static final String LOGS            = "staff.logs";
	public static final String APPEALS         = "staff.appeals";
	public static final String SNAPSHOT_REMOVE = "security.invsee.snapshots.remove";
	public static final String ENDERCHEST      = "security.enderchest";
	public static final String CONFISCATE      = "security.confiscate";
	public static final String BLOCK_INSPECT   = "grief.inspect";
	public static final String APPEAL_USE      = "appeal.use";   // open to all

	// addons
	public static final String INVSEE          = "security.invsee";
	public static final String INVSEE_EDIT     = "security.invsee.edit";
	public static final String ITEMSCAN        = "security.itemscanner";
	public static final String SECURITY_CHECK  = "security.check";
	/** View the contraband vault and hand items back. */
	public static final String VAULT           = "security.vault";
	/** Destroy a vaulted item for good, and edit the contraband rule list. */
	public static final String VAULT_DESTROY   = "security.vault.destroy";
	public static final String CONTRABAND_EDIT = "security.contraband.edit";
	public static final String ANALYTICS       = "analytics.stats";
	public static final String ROLLBACK        = "grief.rollback";
	public static final String ROLLBACK_REGEN  = "grief.rollback.regen";
	/** Query the block and container logs with {@code /staff search}. */
	public static final String GRIEF_SEARCH    = "grief.search";
	/** Delete log history early with {@code /staff purge}. */
	public static final String GRIEF_PURGE     = "grief.purge";
	/** Leave inspect mode on so any block click reports its history. */
	public static final String INSPECT_MODE    = "grief.inspect.mode";
	/** Edit the built-in permission groups used when no permissions mod is installed. */
	public static final String PERMS_ADMIN     = "staff.perms";
	public static final String SPY             = "control.spy";
	public static final String CHAT_CONTROL    = "control.chat";
	public static final String BROADCAST       = "control.broadcast";
	public static final String MAINTENANCE     = "control.maintenance";
	public static final String PROXY           = "proxy.admin";

	// accountability
	/** Read another staff member's action history. */
	public static final String AUDIT           = "staff.audit";
	/**
	 * Read the addresses a staff member acted from.
	 * <p>
	 * Separate from {@link #AUDIT} and deliberately admin-only. It is for establishing whether
	 * a staff account was compromised, which is a question worth being able to answer and not
	 * one every staff member needs to be able to ask about their colleagues.
	 */
	public static final String AUDIT_ADDRESSES = "staff.audit.addresses";
	/** Skip the per-minute limits. For admins doing a genuine bulk cleanup. */
	public static final String RATE_LIMIT_EXEMPT = "staff.ratelimit.exempt";
	/** Confirm somebody else's staged mass rollback, IP ban or inventory edit. */
	public static final String APPROVE         = "staff.approve";
}
