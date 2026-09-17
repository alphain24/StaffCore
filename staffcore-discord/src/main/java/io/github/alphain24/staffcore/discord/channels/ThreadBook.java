package io.github.alphain24.staffcore.discord.channels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Where each report, appeal, case and punishment was posted, so a later change edits the same
 * message and speaks in the same thread — across restarts too.
 * <p>
 * The companion's own memory, kept beside the world in a small JSON file, and deliberately not in
 * StaffCore's database: it is a cache of Discord ids, not a record of anything that happened, and
 * losing it costs a new message where an edit would have been. Entries older than
 * {@value #KEEP_DAYS} days are forgotten on load; Discord archives an idle thread long before then.
 */
public final class ThreadBook {

	static final int KEEP_DAYS = 90;

	/**
	 * Where one thing was posted.
	 *
	 * @param threadId the thread started on it, or null
	 * @param message  what was posted, so it can be changed and posted again as an edit
	 */
	public record Entry(String channelId, String messageId, String threadId, long postedAt, Outbound.Message message) {

		public Entry withMessage(Outbound.Message changed) {
			return new Entry(channelId, messageId, threadId, postedAt, changed);
		}
	}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private final LongSupplier clock;
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private String lastProblem;

	/** @param file where the book is kept; null keeps it in memory only */
	public ThreadBook(Path file, LongSupplier clock) {
		this.file = file;
		this.clock = clock;
		load();
	}

	public synchronized Entry get(String key) {
		return key == null ? null : entries.get(key);
	}

	/** Whether this key has a thread to speak in. */
	public synchronized boolean hasThread(String key) {
		Entry entry = get(key);
		return entry != null && entry.threadId() != null;
	}

	public synchronized void put(String key, Entry entry) {
		if (key == null || entry == null) return;
		entries.put(key, entry);
		save();
	}

	/** A file of this name beside the book, for the companion's other memory; null when the book has no file. */
	public Path beside(String name) {
		return file == null ? null : file.resolveSibling(name);
	}

	/** A problem reading or writing the file, for {@code /staff status}; null when there is none. */
	public synchronized String problem() {
		return lastProblem;
	}

	// ------------------------------------------------------------------ file

	private void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Entry> read = GSON.fromJson(reader, new TypeToken<Map<String, Entry>>() {}.getType());
			if (read == null) return;
			long cutoff = clock.getAsLong() - KEEP_DAYS * 86_400_000L;
			read.forEach((key, entry) -> {
				if (entry != null && entry.postedAt() >= cutoff) entries.put(key, entry);
			});
		} catch (IOException | JsonParseException e) {
			// Started empty rather than refused: the worst this costs is new messages where edits
			// would have gone.
			lastProblem = "the thread book could not be read (" + e.getClass().getSimpleName()
					+ "); earlier posts will not be edited";
		}
	}

	private void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(entries, writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			lastProblem = null;
		} catch (IOException e) {
			lastProblem = "the thread book could not be saved (" + e.getClass().getSimpleName() + ")";
		}
	}
}
