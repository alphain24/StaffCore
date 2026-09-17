package io.github.alphain24.staffcore.discord.channels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealPanelTest {

	@Test
	@DisplayName("the panel says where the code is and carries one button that opens the form")
	void panel() {
		Outbound.Message panel = AppealPanel.message();
		assertTrue(panel.embed().title().contains("Appeal"));
		String code = panel.embed().field("1. Find your appeal code");
		assertTrue(code != null && code.contains("join") && code.contains("muted"), String.valueOf(code));
		assertTrue(panel.embed().description().contains("/appeal"));
		assertTrue(panel.embed().field("3. Wait for staff").contains("direct message"));
		assertTrue(panel.embed().field("Good to know").contains("new one"),
				"the panel does not say a rejection brings a new code");
		assertEquals(1, panel.rows().size());
		assertEquals(1, panel.rows().get(0).size());
		assertEquals(AppealPanel.BUTTON_ID, panel.rows().get(0).get(0).id());
		assertTrue(AppealPanel.BUTTON_ID.startsWith("sc:appealpanel:"));
		assertTrue(AppealPanel.FORM_ID.startsWith("sc:appealform:"));
	}
}
