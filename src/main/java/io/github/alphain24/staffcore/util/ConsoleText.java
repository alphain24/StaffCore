package io.github.alphain24.staffcore.util;

/**
 * Text for the server console, in ASCII.
 * <p>
 * Chat and menus travel as UTF-8 inside Minecraft's own packets and may say what they like. The
 * console lands in whatever code page the host uses, and on Windows that is a legacy one: an em
 * dash arrives as mojibake. Alerts are written once for chat and mirrored to the log, so the
 * mirror goes through here rather than every sentence being written twice.
 */
public final class ConsoleText {
	private ConsoleText() {}

	/** The same sentence with its typography made ASCII, and anything else non-ASCII as '?'. */
	public static String ascii(String text) {
		if (text == null) return "";
		StringBuilder out = null;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 32 && c <= 126 || c == '\t') {
				if (out != null) out.append(c);
				continue;
			}
			if (out == null) out = new StringBuilder(text.length() + 8).append(text, 0, i);
			switch (c) {
				case '\u2014', '\u2013', '\u2212' -> out.append('-');
				case '\u2026' -> out.append("...");
				case '\u00B7', '\u2022' -> out.append('-');
				case '\u2018', '\u2019' -> out.append('\'');
				case '\u201C', '\u201D' -> out.append('"');
				case '\u00D7' -> out.append('x');
				case '\u2192' -> out.append("->");
				case '\u00A0' -> out.append(' ');
				case '\n', '\r' -> out.append(' ');
				default -> {
					if (Character.isHighSurrogate(c) && i + 1 < text.length()
							&& Character.isLowSurrogate(text.charAt(i + 1))) {
						i++;
					}
					out.append('?');
				}
			}
		}
		return out == null ? text : out.toString();
	}
}
