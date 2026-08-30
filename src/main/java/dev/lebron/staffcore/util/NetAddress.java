package dev.lebron.staffcore.util;

/**
 * The parts of a connection address StaffCore is allowed to reason about.
 * <p>
 * Alt detection used to compare addresses for exact equality, which quietly assumed home
 * addresses are stable. They are not: a router reboot or a lease expiry moves a player
 * within their provider's block, and the two accounts stop looking related at all. That is
 * most of what "a VPN defeats it" actually meant in practice — no VPN required, just a
 * week.
 * <p>
 * A range match is a genuinely weaker signal and is scored as one. An entire university
 * shares a /24; so does a block of flats. It is a reason to look, never a reason to act,
 * and nothing here can trigger an automatic ban on its own.
 */
public final class NetAddress {
	private NetAddress() {}

	/**
	 * The network part of an address — the first three parts of an IPv4 address, or the
	 * first four groups of an IPv6 one.
	 *
	 * @return null for anything unparseable, which callers treat as "no range information"
	 *         rather than as a range that happens to match other unparseable addresses
	 */
	public static String prefix(String ip) {
		if (ip == null || ip.isBlank() || "unknown".equals(ip)) return null;

		if (ip.indexOf(':') >= 0) {
			// IPv6: the /64 that a single site is normally delegated.
			String[] groups = ip.split(":");
			if (groups.length < 4) return null;
			return String.join(":", groups[0], groups[1], groups[2], groups[3]);
		}

		int last = ip.lastIndexOf('.');
		if (last <= 0) return null;

		String prefix = ip.substring(0, last);
		// Three parts means three dots minus one; anything else is not a dotted quad.
		return prefix.chars().filter(c -> c == '.').count() == 2 ? prefix : null;
	}

	/** True when two addresses sit in the same range but are not the same address. */
	public static boolean sameRange(String a, String b) {
		if (a == null || b == null || a.equals(b)) return false;
		String pa = prefix(a);
		return pa != null && pa.equals(prefix(b));
	}
}
