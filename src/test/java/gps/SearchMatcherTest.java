package gps;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The destination search's fuzzy matcher and persisted history: tier ordering (exact &gt; prefix
 * &gt; word prefixes &gt; substring &gt; subsequence), the abbreviation cases the fuzziness exists
 * for, and the history's dedupe/cap/round-trip behaviour.
 */
public class SearchMatcherTest
{
	@Test
	public void tiersRankTighterMatchesHigher()
	{
		int exact = SearchMatcher.score("Varrock", "varrock");
		int prefix = SearchMatcher.score("Varrock Palace", "varrock");
		int wordPrefix = SearchMatcher.score("East Varrock Mine", "var mine");
		int substring = SearchMatcher.score("East Varrock", "arrock");
		int subsequence = SearchMatcher.score("Varrock", "vrck");
		assertTrue("exact > prefix", exact > prefix);
		assertTrue("prefix > word prefix", prefix > wordPrefix);
		assertTrue("word prefix > substring", wordPrefix > substring);
		assertTrue("substring > subsequence", substring > subsequence);
		assertTrue("subsequence still matches", subsequence > 0);
	}

	@Test
	public void wordPrefixesMatchInOrder()
	{
		assertTrue(SearchMatcher.score("Varrock Palace", "var pal") > 0);
		assertTrue(SearchMatcher.score("Seers' Village Rooftop Course", "seers roof") > 0);
		assertEquals("tokens out of order must not match as word prefixes or better",
			0, SearchMatcher.score("Varrock Palace", "pal var"));
	}

	@Test
	public void subsequenceToleratesDroppedLetters()
	{
		assertTrue(SearchMatcher.score("Brimhaven Agility Arena", "brmhvn") > 0);
		assertTrue(SearchMatcher.score("Gemstone crabs (Tal Teklan)", "gmstn crb") > 0);
		assertEquals("letters out of order never match", 0, SearchMatcher.score("Brimhaven", "nvhmrb"));
		assertEquals("too-short queries don't subsequence-match", 0, SearchMatcher.score("Brimhaven", "bn"));
	}

	@Test
	public void shorterNamesWinWithinATier()
	{
		assertTrue(SearchMatcher.score("Varrock", "var") > SearchMatcher.score("Varrock Palace", "var"));
	}

	@Test
	public void historyPushDedupesAndCaps()
	{
		List<Destinations.Entry> history = List.of();
		for (int i = 0; i < 15; i++)
		{
			history = SearchHistory.push(history, new Destinations.Entry("place", "Place " + i, i));
		}
		assertEquals(SearchHistory.LIMIT, history.size());
		assertEquals("Place 14", history.get(0).name);

		// Re-selecting an existing entry moves it to the front without duplicating it.
		history = SearchHistory.push(history, new Destinations.Entry("place", "Place 12", 12));
		assertEquals(SearchHistory.LIMIT, history.size());
		assertEquals("Place 12", history.get(0).name);
		assertEquals(1, history.stream().filter(e -> e.name.equals("Place 12")).count());
	}

	@Test
	public void historySurvivesARoundTrip()
	{
		List<Destinations.Entry> history = List.of(
			new Destinations.Entry("training", "Brimhaven Agility Arena", 12345),
			new Destinations.Entry("place", "Seers' Village", -99));
		List<Destinations.Entry> back = SearchHistory.deserialize(SearchHistory.serialize(history));
		assertEquals(2, back.size());
		assertEquals("Brimhaven Agility Arena", back.get(0).name);
		assertEquals("training", back.get(0).category);
		assertEquals(12345, back.get(0).packedPosition);
		assertEquals(-99, back.get(1).packedPosition);
	}

	@Test
	public void deserializeToleratesJunk()
	{
		assertTrue(SearchHistory.deserialize(null).isEmpty());
		assertTrue(SearchHistory.deserialize("").isEmpty());
		assertEquals(1, SearchHistory.deserialize("place\tValid\t5\ngarbage line\nplace\tNaN\tx").size());
	}
}
