package io.github.alphain24.staffcore.modules.appeal;

import java.net.URI;
import java.util.Locale;

/**
 * The configured Discord invite as a link a client will open.
 * <p>
 * Only somewhere a client lets text be clicked: chat. The ban screen does not — the 26.2 client
 * never gives the disconnect screen a click handler, so a link there is text to type whatever
 * the server sends. See the "Why the ban screen has no buttons" entry in docs/decisions.md.
 */
public final class InviteLink {
	private InviteLink() {}

	/**
	 * The invite as a URI, or {@code null} when it is not a web link.
	 * <p>
	 * "discord.gg/abc" is how people paste invites, and it is not a URL: without a scheme the
	 * client refuses to open it. So a bare host gets https, and anything that is still not an
	 * http(s) link with a host is left as plain text rather than made into a dead link.
	 */
	public static URI of(String invite) {
		if (invite == null || invite.isBlank()) return null;
		String trimmed = invite.trim();
		if (!trimmed.contains("://")) trimmed = "https://" + trimmed;
		try {
			URI uri = new URI(trimmed);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("https") && !scheme.equals("http")) return null;
			if (uri.getHost() == null || !uri.getHost().contains(".")) return null;
			return uri;
		} catch (java.net.URISyntaxException e) {
			return null;
		}
	}
}
