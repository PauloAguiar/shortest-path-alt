package gps;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The "report an issue" button opens a GitHub new-issue page pre-filled with the routing context.
 * The URL must encode the title and body so GitHub receives them intact — special characters
 * (newlines, the middle-dot and ellipsis the summary uses, markdown) round-trip through the encoder.
 */
public class IssueReportUrlTest
{
	@Test
	public void bodyRoundTripsThroughTheUrl() throws Exception
	{
		String body = "**Describe the issue**\n\n---\n- GPS 0.11.0\n- Routes (2):\n"
			+ "  0. 102 · Amulet of glory: Edgeville\n  … 1 more\n";
		String url = ShortestPathPlugin.issueUrl("[Bug] ", body);

		assertTrue("must target the GitHub new-issue endpoint",
			url.startsWith("https://github.com/PauloAguiar/runelite-gps-plugin/issues/new?title="));

		Matcher m = Pattern.compile("[?&]body=([^&]*)").matcher(url);
		assertTrue("the URL must carry a body param", m.find());
		String decoded = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8.name());
		assertEquals("the body must survive encode/decode intact", body, decoded);

		Matcher t = Pattern.compile("[?&]title=([^&]*)").matcher(url);
		assertTrue(t.find());
		assertEquals("[Bug] ", URLDecoder.decode(t.group(1), StandardCharsets.UTF_8.name()));
	}

	@Test
	public void versionIsReadFromTheBundledManifest()
	{
		// build.gradle bundles runelite-plugin.properties onto the classpath, so the plugin reports
		// its real release version (not a hard-coded constant that can drift).
		String version = ShortestPathPlugin.pluginVersion();
		assertTrue("version must be read from the manifest, not the 'unknown' fallback: " + version,
			version.matches("\\d+\\.\\d+\\.\\d+"));
	}
}
