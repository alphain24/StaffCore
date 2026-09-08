package io.github.alphain24.staffcore.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A permission check must name a node from {@link Nodes}, never a string literal.
 * <p>
 * {@code ActorBoundaryTest} checks one direction — that every node declared in {@link Nodes} is
 * enumerated, so none is silently denied. This is the other direction, and it is the one with
 * the nastier failure.
 * <p>
 * {@link Actor} resolves permissions eagerly by walking the declared nodes. A check written as
 * {@code actor.has("staff.newthing")} asks about a string that was never in that walk, so it is
 * absent from every actor's resolved set and <b>denied to everybody, including operators</b> —
 * except that operators pass via the {@code operator} fallback, which means it is denied to
 * everybody <em>except</em> operators. That is worse than a plain denial: it works perfectly
 * for whoever is testing it, because whoever is testing it is opped.
 * <p>
 * A typo has the same shape. {@code "staff.punsh"} compiles, resolves to nothing, and quietly
 * removes a permission from everyone who is not an operator.
 */
class NodeLiteralTest {

	private static final Path SOURCE = Path.of("src", "main", "java");

	/**
	 * Permission-check call sites.
	 * <p>
	 * {@code has} is included because {@link Actor#has} is the post-refactor form and takes the
	 * same kind of argument. Matching is textual and deliberately so — anything cleverer would
	 * need the code it is checking to be correct in order to work.
	 */
	private static final Pattern CALL = Pattern.compile(
			"\\b(?:Permissions\\.(?:check|checkOpen|checkAny)|\\w+\\.has)\\s*\\(");

	private record Offence(String file, int line, String snippet) {}

	@Test
	@DisplayName("no permission check passes a string literal instead of a Nodes constant")
	void everyCheckNamesADeclaredNode() throws IOException {
		List<Offence> offences = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = SOURCE.relativize(file).toString().replace('\\', '/');

				// Nodes declares the literals; Actor and Accountable reason about them as data.
				if (relative.endsWith("permission/Nodes.java")) continue;

				String body = Files.readString(file, StandardCharsets.UTF_8);
				Matcher m = CALL.matcher(body);

				while (m.find()) {
					String args = arguments(body, m.end() - 1);
					if (args == null || !args.contains("\"")) continue;

					offences.add(new Offence(relative, lineOf(body, m.start()), condense(args)));
				}
			}
		}

		assertTrue(offences.isEmpty(), () -> """
				A permission check names a string literal rather than a Nodes constant.

				This is worse than it looks. Actor resolves permissions by walking the nodes \
				declared in Nodes, so a literal that is not one of them resolves to nothing — \
				denied to every player, and silently granted to operators through the operator \
				fallback. It therefore works perfectly for whoever is testing it, because \
				whoever is testing it is opped.

				Declare it in Nodes and reference it:
				""" + offences.stream()
						.map(o -> "  " + o.file() + ":" + o.line() + "  " + o.snippet())
						.reduce("", (a, b) -> a + "\n" + b));
	}

	@Test
	@DisplayName("the scan actually finds call sites, so an empty result means something")
	void theScanIsNotVacuous() throws IOException {
		// A regex that matches nothing passes this file's other test forever while the codebase
		// fills with literals. Counting what it did find is what makes the pass meaningful.
		int found = 0;

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				Matcher m = CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
				while (m.find()) found++;
			}
		}

		assertTrue(found > 50,
				"the permission-check scan matched only " + found + " call sites, which is far "
						+ "fewer than this codebase has. The pattern has stopped matching and "
						+ "the literal check above is passing vacuously.");
	}

	@Test
	@DisplayName("a literal would be caught, proving the scan works")
	void theScanCatchesWhatItClaimsTo() {
		// Verified against a sample rather than trusted. The check is only worth having if it
		// would fire, and the cheapest way to know is to hand it something that should fire.
		String sample = """
				class Example {
					void go() {
						if (Permissions.check(player, "staff.newthing")) return;
					}
				}
				""";

		Matcher m = CALL.matcher(sample);
		assertTrue(m.find(), "the pattern did not match an obvious call site");

		String args = arguments(sample, m.end() - 1);
		assertTrue(args != null && args.contains("\""),
				"the argument scan did not see the literal it was pointed at");
	}

	/**
	 * The argument list of a call, given the index of its opening bracket.
	 * <p>
	 * Bracket-counting rather than a regex, because a call can span lines and contain nested
	 * calls — and a regex that stops at the first {@code )} would read half an argument list
	 * and miss whatever came after it.
	 */
	private static String arguments(String body, int openIndex) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int i = openIndex; i < body.length(); i++) {
			char c = body.charAt(i);

			if (inString) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') inString = false;
				continue;
			}
			switch (c) {
				case '"' -> inString = true;
				case '(' -> depth++;
				case ')' -> {
					depth--;
					if (depth == 0) return body.substring(openIndex + 1, i);
				}
				default -> { }
			}
		}
		return null;
	}

	private static int lineOf(String body, int index) {
		int line = 1;
		for (int i = 0; i < index && i < body.length(); i++) {
			if (body.charAt(i) == '\n') line++;
		}
		return line;
	}

	private static String condense(String args) {
		String flat = args.replaceAll("\\s+", " ").trim();
		return flat.length() > 90 ? flat.substring(0, 87) + "..." : flat;
	}
}
