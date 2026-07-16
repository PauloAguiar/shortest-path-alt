package gps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The ETA must count the bank detour so it never disagrees with the ranking. From a user capture:
 * a direct route (rawCost 104, no bank) was ranked ABOVE a banking route (rawCost 84) whose total
 * cost was 114 after the +30 bank-pickup — but the ETA showed only the banking route's 84, making
 * the top route look slower. The ETA now includes the bank pickup, so time order matches rank.
 */
public class RouteEtaTest
{
	@Test
	public void bankDetourCountsTowardTheEta()
	{
		int direct = ShortestPathPanel.etaUnits(104, false, 30);
		int banking = ShortestPathPanel.etaUnits(84, true, 30);
		assertEquals("a direct route's ETA is just its travel cost", 104, direct);
		assertEquals("a banking route's ETA adds the bank-pickup time", 84 + 30, banking);
		assertTrue("the higher-ranked (cheaper total) route must not show a longer ETA",
			direct <= banking);
	}

	@Test
	public void negativeBankModifierIsAPreferenceNotNegativeTime()
	{
		// A negative costBankPickup favours banking in the ordering, but banking can't take less
		// than the raw travel time — the ETA never dips below rawCost.
		assertEquals(84, ShortestPathPanel.etaUnits(84, true, -50));
		assertEquals(84, ShortestPathPanel.etaUnits(84, false, -50));
	}
}
