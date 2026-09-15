package io.github.alphain24.staffcore.discord.channels;

import java.util.ArrayList;
import java.util.List;

/**
 * An embed, as plain data.
 * <p>
 * Built and tested without Discord, then turned into JDA's type at the last moment. Discord refuses
 * an embed that breaks any of its limits — the whole message fails, not just the long field — so
 * the limits are enforced here, where a test can see them, rather than discovered when a long
 * report reason makes a post silently disappear.
 *
 * @param title       at most {@value #TITLE} characters
 * @param description at most {@value #DESCRIPTION} characters, or null
 * @param thumbnail   an image address, or null
 * @param footer      at most {@value #FOOTER} characters, or null
 * @param timestamp   epoch milliseconds, or null
 */
public record Embed(String title, String description, int color, List<Field> fields, String thumbnail,
		String footer, Long timestamp) {

	static final int TITLE = 256;
	static final int DESCRIPTION = 4096;
	static final int FIELD_NAME = 256;
	static final int FIELD_VALUE = 1024;
	static final int FOOTER = 2048;
	static final int MAX_FIELDS = 25;
	/** Discord's limit on everything in one embed put together. */
	static final int TOTAL = 6000;

	/** One field. An empty value is refused by Discord, so it becomes a dash. */
	public record Field(String name, String value, boolean inline) {

		public Field {
			name = Text.clip(name == null || name.isBlank() ? "—" : name, FIELD_NAME);
			value = Text.clip(value == null || value.isBlank() ? "—" : value, FIELD_VALUE);
		}
	}

	public Embed {
		// Each part within its own limit and all of them within the total: at their separate maximums
		// a title, description and footer come to more than Discord allows in one embed.
		title = Text.clip(title == null ? "" : title, TITLE);
		footer = footer == null ? null : Text.clip(footer, Math.min(FOOTER, TOTAL - title.length()));
		int footerLength = footer == null ? 0 : footer.length();
		description = description == null ? null
				: Text.clip(description, Math.min(DESCRIPTION, TOTAL - title.length() - footerLength));
		List<Field> kept = new ArrayList<>();
		int total = title.length() + (description == null ? 0 : description.length())
				+ (footer == null ? 0 : footer.length());
		for (Field field : fields == null ? List.<Field>of() : fields) {
			if (kept.size() >= MAX_FIELDS) break;
			int size = field.name().length() + field.value().length();
			if (total + size > TOTAL) break;
			total += size;
			kept.add(field);
		}
		fields = List.copyOf(kept);
	}

	/** The value of the first field with this name, or null. */
	public String field(String name) {
		for (Field field : fields) {
			if (field.name().equals(name)) return field.value();
		}
		return null;
	}

	/** This embed with the named field's value replaced, or the field added at the end. */
	public Embed withField(String name, String value, boolean inline) {
		List<Field> changed = new ArrayList<>(fields);
		boolean replaced = false;
		for (int i = 0; i < changed.size(); i++) {
			if (changed.get(i).name().equals(name)) {
				changed.set(i, new Field(name, value, changed.get(i).inline()));
				replaced = true;
				break;
			}
		}
		if (!replaced) changed.add(new Field(name, value, inline));
		return new Embed(title, description, color, changed, thumbnail, footer, timestamp);
	}

	public Embed withColor(int newColor) {
		return new Embed(title, description, newColor, fields, thumbnail, footer, timestamp);
	}

	/** A small builder, so the embed code reads top to bottom like the message it makes. */
	public static Builder builder(String title) {
		return new Builder(title);
	}

	public static final class Builder {
		private final String title;
		private String description;
		private int color;
		private final List<Field> fields = new ArrayList<>();
		private String thumbnail;
		private String footer;
		private Long timestamp;

		private Builder(String title) {
			this.title = title;
		}

		public Builder description(String text) {
			this.description = text;
			return this;
		}

		public Builder color(int rgb) {
			this.color = rgb;
			return this;
		}

		public Builder field(String name, String value) {
			fields.add(new Field(name, value, false));
			return this;
		}

		public Builder inline(String name, String value) {
			fields.add(new Field(name, value, true));
			return this;
		}

		public Builder thumbnail(String url) {
			this.thumbnail = url;
			return this;
		}

		public Builder footer(String text) {
			this.footer = text;
			return this;
		}

		public Builder timestamp(long epochMillis) {
			this.timestamp = epochMillis;
			return this;
		}

		public Embed build() {
			return new Embed(title, description, color, fields, thumbnail, footer, timestamp);
		}
	}
}
