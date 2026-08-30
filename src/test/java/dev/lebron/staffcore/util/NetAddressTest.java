package dev.lebron.staffcore.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Address ranges, which decide whether two accounts get linked.
 * <p>
 * Worth testing precisely because the failure is silent in both directions: too loose and
 * strangers are named as alts of each other, too strict and the feature quietly does nothing.
 */
class NetAddressTest {

	@Test
	@DisplayName("an IPv4 address reduces to its first three parts")
	void ipv4Prefix() {
		assertEquals("203.0.113", NetAddress.prefix("203.0.113.40"));
		assertEquals("10.0.0", NetAddress.prefix("10.0.0.255"));
	}

	@Test
	@DisplayName("an IPv6 address reduces to its first four groups")
	void ipv6Prefix() {
		assertEquals("2001:db8:85a3:0", NetAddress.prefix("2001:db8:85a3:0:0:8a2e:370:7334"));
	}

	@Test
	@DisplayName("anything unparseable has no range rather than a wrong one")
	void unparseable() {
		assertNull(NetAddress.prefix(null));
		assertNull(NetAddress.prefix(""));
		assertNull(NetAddress.prefix("unknown"));
		assertNull(NetAddress.prefix("localhost"));
		assertNull(NetAddress.prefix("10.0.0"), "three parts is not a dotted quad");
	}

	@Test
	@DisplayName("two addresses in one block are the same range")
	void sameRange() {
		assertTrue(NetAddress.sameRange("203.0.113.40", "203.0.113.91"),
				"the case the whole feature exists for: a home address that rotated");
	}

	@Test
	@DisplayName("an identical address is not a range match")
	void identicalIsNotARangeMatch() {
		// The exact-address pass already claims these. Counting them twice would let the
		// weaker signal overwrite the stronger one.
		assertFalse(NetAddress.sameRange("203.0.113.40", "203.0.113.40"));
	}

	@Test
	@DisplayName("different blocks are not linked")
	void differentBlocks() {
		assertFalse(NetAddress.sameRange("203.0.113.40", "203.0.114.40"));
		assertFalse(NetAddress.sameRange("203.0.113.40", "unknown"));
	}
}
