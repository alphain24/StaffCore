package io.github.alphain24.staffcore.discord.channels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The requests players open from the public contact channel: who asked, as whom, where the conversation
 * is, and whether it is still open.
 * <p>
 * Kept by the companion beside the world, like the thread book, and not in StaffCore's database. A request
 * is a Discord conversation; what StaffCore needs to know about it — that it happened, and who answered —
 * is in its alerts and its audit log. Closed requests are forgotten after {@value #KEEP_DAYS} days.
 */
public final class HelpDesk {

	static final int KEEP_DAYS = 90;

	/**
	 * One request.
	 *
	 * @param minecraftName the name the player typed, or the server's spelling of it
	 * @param playerId      the player, when the server knows the name; null otherwise
	 * @param linkedTo      the Minecraft name the asking account is linked to, or null
	 * @param threadId      the private thread the player and staff talk in
	 * @param cardChannelId where the staff card is, with {@code cardMessageId}; null when it could not be posted
	 * @param staff         who has joined, as their Minecraft names
	 */
	public record Request(long id, String discordId, String discordName, String minecraftName, UUID playerId,
			String linkedTo, boolean online, boolean frozen, boolean banned, String caseId, String text,
			String threadId, String cardChannelId, String cardMessageId, long openedAt, Long closedAt,
			String closedBy, List<String> staff) {

		public Request {
			staff = staff == null ? List.of() : List.copyOf(staff);
		}

		public boolean open() {
			return closedAt == null;
		}

		public Request joinedBy(String name) {
			if (name == null || staff.contains(name)) return this;
			List<String> more = new ArrayList<>(staff);
			more.add(name);
			return new Request(id, discordId, discordName, minecraftName, playerId, linkedTo, online, frozen, banned,
					caseId, text, threadId, cardChannelId, cardMessageId, openedAt, closedAt, closedBy, more);
		}

		public Request closed(String by, long at) {
			return new Request(id, discordId, discordName, minecraftName, playerId, linkedTo, online, frozen, banned,
					caseId, text, threadId, cardChannelId, cardMessageId, openedAt, at, by, staff);
		}

		public Request withCard(String channelId, String messageId) {
			return new Request(id, discordId, discordName, minecraftName, playerId, linkedTo, online, frozen, banned,
					caseId, text, threadId, channelId, messageId, openedAt, closedAt, closedBy, staff);
		}
	}

	private record Saved(long next, Map<Long, Request> requests) {}

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private final LongSupplier clock;
	private final Map<Long, Request> requests = new LinkedHashMap<>();
	private long next = 1;
	private String lastProblem;

	/** @param file where requests are kept; null keeps them in memory only */
	public HelpDesk(Path file, LongSupplier clock) {
		this.file = file;
		this.clock = clock;
		load();
	}

	/** The number the next request gets. Taken once, so two requests at the same moment never share one. */
	public synchronized long take() {
		long id = next++;
		save();
		return id;
	}

	public synchronized Request get(long id) {
		return requests.get(id);
	}

	/** This account's request that is still open, or null. One at a time: more is a second conversation to lose. */
	public synchronized Request openFor(String discordId) {
		if (discordId == null) return null;
		for (Request request : requests.values()) {
			if (request.open() && discordId.equals(request.discordId())) return request;
		}
		return null;
	}

	public synchronized void put(Request request) {
		if (request == null) return;
		requests.put(request.id(), request);
		save();
	}

	/** Every request still open, oldest first. */
	public synchronized List<Request> open() {
		return requests.values().stream().filter(Request::open).toList();
	}

	/** A problem reading or writing the file, for {@code /staff status}; null when there is none. */
	public synchronized String problem() {
		return lastProblem;
	}

	private void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Saved saved = GSON.fromJson(reader, Saved.class);
			if (saved == null) return;
			next = Math.max(1, saved.next());
			long cutoff = clock.getAsLong() - KEEP_DAYS * 86_400_000L;
			if (saved.requests() != null) {
				saved.requests().forEach((id, request) -> {
					if (request == null) return;
					if (request.open() || request.closedAt() >= cutoff) requests.put(request.id(), request);
					next = Math.max(next, request.id() + 1);
				});
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			lastProblem = "the help requests could not be read (" + e.getClass().getSimpleName()
					+ "); requests from before are not tracked, and numbers start again";
		}
	}

	private void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(new Saved(next, requests), writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			lastProblem = null;
		} catch (IOException e) {
			lastProblem = "the help requests could not be saved (" + e.getClass().getSimpleName() + ")";
		}
	}
}
