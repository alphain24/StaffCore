package io.github.alphain24.staffcore.discord.evidence;

import io.github.alphain24.staffcore.api.DiscordEvidenceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping files filed as evidence: under the case, named by hash, bounded, and never runnable.
 */
class EvidenceLockerTest {

	/** What StaffCore accepts as a kept file's place; the same pattern as DiscordEvidenceStore.STORED_PATH. */
	private static final Pattern STORED = Pattern.compile("[A-Z0-9]{8}/[0-9a-f]{64}\\.[a-z0-9]{1,5}");

	@TempDir
	Path folder;

	private static ByteArrayInputStream bytes(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("a file is kept under its case, named by its SHA-256, in a place StaffCore accepts")
	void keeps() throws IOException {
		DiscordEvidenceFile kept = EvidenceLocker.keep(folder, "ABCD2345", "x-ray proof.PNG", "image/png",
				bytes("not really a png"), 1024);
		assertNotNull(kept.storedPath(), kept.notKeptWhy());
		assertTrue(STORED.matcher(kept.storedPath()).matches(), kept.storedPath());
		assertEquals("ABCD2345/" + kept.sha256() + ".png", kept.storedPath());
		assertEquals("not really a png", Files.readString(folder.resolve(kept.storedPath())));
		assertEquals(16, kept.sizeBytes());
		assertEquals("x-ray proof.PNG", kept.name());

		// The same file twice is kept once.
		DiscordEvidenceFile again = EvidenceLocker.keep(folder, "ABCD2345", "copy.png", "image/png",
				bytes("not really a png"), 1024);
		assertEquals(kept.storedPath(), again.storedPath());
		try (Stream<Path> files = Files.list(folder.resolve("ABCD2345"))) {
			assertEquals(1, files.count(), "a duplicate or a leftover part file was left behind");
		}
	}

	@Test
	@DisplayName("a stream longer than the limit is not kept, and leaves nothing behind")
	void tooLarge() throws IOException {
		DiscordEvidenceFile file = EvidenceLocker.keep(folder, "ABCD2345", "big.mp4", "video/mp4",
				bytes("x".repeat(2000)), 1024);
		assertNull(file.storedPath());
		assertTrue(file.notKeptWhy().contains("larger"), file.notKeptWhy());
		try (Stream<Path> files = Files.list(folder.resolve("ABCD2345"))) {
			assertEquals(0, files.count(), "a partial download was left in the case folder");
		}
	}

	@Test
	@DisplayName("nothing is written for a case id that is not one, so no path can leave the folder")
	void badCase() {
		DiscordEvidenceFile file = EvidenceLocker.keep(folder, "../../etc", "a.txt", "text/plain", bytes("x"), 1024);
		assertNull(file.storedPath());
		assertFalse(Files.exists(folder.resolve("../../etc")));
	}

	@Test
	@DisplayName("only kinds a person opens keep their extension; anything else is .bin")
	void extensions() {
		assertEquals("jpg", EvidenceLocker.extension("photo.JPEG"));
		assertEquals("mp4", EvidenceLocker.extension("clip.mp4"));
		assertEquals("log", EvidenceLocker.extension("latest.log"));
		assertEquals("bin", EvidenceLocker.extension("payload.exe"));
		assertEquals("bin", EvidenceLocker.extension("script.bat"));
		assertEquals("bin", EvidenceLocker.extension("noextension"));
		assertEquals("bin", EvidenceLocker.extension("trailing."));
		assertEquals("bin", EvidenceLocker.extension(null));
	}
}
