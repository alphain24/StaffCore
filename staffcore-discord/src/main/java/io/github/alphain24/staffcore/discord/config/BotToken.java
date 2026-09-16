package io.github.alphain24.staffcore.discord.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bot token, kept where it cannot leak by accident.
 *
 * <h2>Its own file</h2>
 * Never {@code staffcore.json}, and never the companion's settings file either. Settings get
 * pasted into support channels, committed to repositories and attached to bug reports; a file
 * whose only content is a secret gets treated like one. So the token lives alone in
 * {@value #SHOWN} next to the other config files, and nothing else is ever written there.
 *
 * <h2>Never printed</h2>
 * {@link #toString} is redacted, so a token that ends up in a string concatenation or a debugger
 * shows nothing. Every problem this class reports describes the file, never its contents — a
 * "does not look like a token" message that quoted what it found would print the token whenever
 * somebody pasted it with a stray character. {@link #redact} scrubs it out of any text before that
 * text is logged or shown.
 */
public final class BotToken {

	public static final String FILE_NAME = "discord.token";
	/** What earlier builds called it, loose in {@code config/}. */
	public static final String LEGACY_FILE_NAME = "staffcore-discord.token";
	/** The file as an owner finds it from the server folder. */
	public static final String SHOWN = "config/staffcore/" + FILE_NAME;

	/**
	 * Three base64url parts separated by dots, the shape Discord issues. Checked so a pasted client
	 * secret or application id is reported as the wrong thing rather than rejected by Discord with a
	 * less helpful error.
	 */
	private static final Pattern SHAPE =
			Pattern.compile("[A-Za-z0-9_-]{20,}[.][A-Za-z0-9_-]{4,}[.][A-Za-z0-9_-]{20,}");

	private final String value;

	private BotToken(String value) {
		this.value = value;
	}

	/**
	 * What reading the file found.
	 *
	 * @param token         the token, or null when there is none to use
	 * @param problem       why there is none, or null; never contains the file's contents
	 * @param worldReadable whether any user on the machine can read the file
	 */
	public record Loaded(BotToken token, String problem, boolean worldReadable) {}

	public static Loaded load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new Loaded(null, "There is no token file. Create " + SHOWN + " containing only the bot token.", false);
		}

		boolean exposed = worldReadable(file);
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			// The exception type only. An I/O message is normally just a path, but "normally" is
			// not the standard for the one file holding a credential.
			return new Loaded(null, "The token file could not be read (" + e.getClass().getSimpleName()
					+ ").", exposed);
		}

		String token = clean(text);
		if (token.isEmpty()) {
			return new Loaded(null, "The token file is empty. Put the bot token in it, on its own.",
					exposed);
		}
		if (!SHAPE.matcher(token).matches()) {
			return new Loaded(null, "The token file does not hold a bot token: a token is three parts "
					+ "separated by dots, from the Bot page of the Discord developer portal. An "
					+ "application id or client secret will not work.", exposed);
		}
		return new Loaded(new BotToken(token), null, exposed);
	}

	/**
	 * Creates the token file empty, if it is not there, so an owner has a file to paste into rather than
	 * one to create and name correctly.
	 * <p>
	 * Never overwrites: a file that exists is somebody's token, or somebody's attempt at one. On a system
	 * with Unix permissions the file is created readable by its owner only, so a token pasted into it is
	 * never world-readable, not even for the minutes before somebody remembers to change it.
	 *
	 * @return true when the file was created by this call
	 */
	public static boolean createIfMissing(Path file) {
		if (Files.exists(file)) return false;
		try {
			Files.createDirectories(file.getParent());
			if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} else {
				Files.createFile(file);
			}
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Strips a byte-order mark, surrounding whitespace and a pasted "Bot " prefix. */
	static String clean(String text) {
		String t = text.replace("\uFEFF", "").strip();
		if (t.regionMatches(true, 0, "Bot ", 0, 4)) t = t.substring(4).strip();
		return t;
	}

	/** Only for logging in to Discord. Nothing else in the companion may call this. */
	public String revealForLogin() {
		return value;
	}

	/** This text with every occurrence of the token replaced. Safe on null. */
	public String redact(String text) {
		if (text == null || value.isEmpty()) return text;
		return text.replace(value, "[token]");
	}

	/** Whether this text contains the token. */
	public boolean appearsIn(String text) {
		return text != null && !value.isEmpty() && text.contains(value);
	}

	@Override
	public String toString() {
		return "BotToken[redacted]";
	}

	// ------------------------------------------------------------------ file permissions

	/**
	 * Principals meaning "anybody with an account on this machine", on Windows: by the name Java
	 * reports ({@code \Everyone} arrives with an empty domain in front), and by the well-known id it
	 * reports instead when the name cannot be looked up.
	 */
	private static final Set<String> BROAD_PRINCIPALS = Set.of(
			"everyone", "builtin\\users", "nt authority\\authenticated users",
			"s-1-1-0", "s-1-5-32-545", "s-1-5-11");

	/**
	 * Whether anybody on the machine besides its owner can read this file.
	 * <p>
	 * Shared hosting puts several customers on one machine, and a token readable by all of them is
	 * readable by the least careful. On Unix that is the "others" read bit; on Windows, an allow
	 * entry granting read to Everyone or to all users. Where neither can be read, the answer is no
	 * — a warning that fires on every start regardless is one people learn to skip.
	 */
	public static boolean worldReadable(Path file) {
		try {
			PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
			if (posix != null) {
				return posix.readAttributes().permissions().contains(PosixFilePermission.OTHERS_READ);
			}
			AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
			if (acl != null) {
				for (AclEntry entry : acl.getAcl()) {
					if (entry.type() != AclEntryType.ALLOW) continue;
					if (!entry.permissions().contains(AclEntryPermission.READ_DATA)) continue;
					String who = entry.principal().getName().toLowerCase(Locale.ROOT);
					if (who.startsWith("\\")) who = who.substring(1);
					if (BROAD_PRINCIPALS.contains(who)) return true;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// Cannot tell; see above.
		}
		return false;
	}
}
