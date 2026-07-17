package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * The Stronghold of Security exit portal (leave-to-surface) and its surface entrance both land on
 * the same tile, so with a zero cost the search used "exit → surface → re-enter" as a FREE hop
 * between two adjacent inside tiles — routing the player needlessly out of the dungeon (user
 * capture 20260716-200841). Giving the exit transports a real duration restores the direct walk.
 */
@RunWith(MockitoJUnitRunner.class)
public class StrongholdExitTest
{
	// Adjacent, directly-walkable inside tiles either side of the exit portal.
	private static final int INSIDE_A = WorldPointUtil.packWorldPoint(2837, 10089, 2);
	private static final int INSIDE_B = WorldPointUtil.packWorldPoint(2837, 10090, 2);

	@Mock Client client;
	@Mock ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	@Test
	public void doesNotLeaveTheStrongholdToCrossOneTile()
	{
		PathfinderConfig cfg = new TestPathfinderConfig(client, config).copyForPlanning();
		cfg.refresh();

		Pathfinder pf = new Pathfinder(cfg, INSIDE_A, Set.of(INSIDE_B));
		pf.run();

		assertTrue(pf.getResult().isReached());
		assertEquals("the two tiles are adjacent — the route must be a single walking step", 1,
			pf.getResult().getTotalCost());
		for (PathStep step : pf.getPath())
		{
			assertTrue("the route must not dip out to the surface: " + step.getPackedPosition(),
				WorldPointUtil.unpackWorldPlane(step.getPackedPosition()) != 0);
		}
	}
}
