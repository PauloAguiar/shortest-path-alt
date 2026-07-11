package gps.pathfinder;

import java.util.List;
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
 * The Auburn Valley Mountain Guide (npc 14529, Quetzacalli Gorge <-> Auburn Valley) must be
 * traversable: both endpoints walkable, the transport registered at each origin, and a route from
 * beside the gorge guide to Auburn Valley taking the guide edge. Guards the gate/data regressions
 * that hid it (an unverifiable exact-equality varbit requirement).
 */
@RunWith(MockitoJUnitRunner.class)
public class MountainGuideTest
{
	private static final int GORGE_GUIDE = WorldPointUtil.packWorldPoint(1486, 3232, 0);
	private static final int AUBURN_GUIDE = WorldPointUtil.packWorldPoint(1361, 3309, 0);

	@Mock
	Client client;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(120);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
	}

	@Test
	public void guideRouteIsTraversableAndTaken()
	{
		PathfinderConfig cfg = new TestPathfinderConfig(client, config).copyForPlanning();
		cfg.refresh();

		assertEquals("guide transport must be registered at the gorge origin", 1,
			cfg.getTransportsPacked(false).getOrDefault(GORGE_GUIDE, TransportAvailability.EMPTY_TRANSPORTS).length);
		assertEquals("guide transport must be registered at the Auburn origin", 1,
			cfg.getTransportsPacked(false).getOrDefault(AUBURN_GUIDE, TransportAvailability.EMPTY_TRANSPORTS).length);

		Pathfinder pathfinder = new Pathfinder(cfg, WorldPointUtil.packWorldPoint(1490, 3230, 0), Set.of(AUBURN_GUIDE));
		pathfinder.run();
		assertTrue("route beside the gorge guide must reach Auburn Valley", pathfinder.getResult().isReached());
		List<PathStep> path = pathfinder.getResult().getPathSteps();
		boolean usedGuide = false;
		for (int i = 1; i < path.size(); i++)
		{
			if (path.get(i - 1).getPackedPosition() == GORGE_GUIDE && path.get(i).getPackedPosition() == AUBURN_GUIDE)
			{
				usedGuide = true;
			}
		}
		assertTrue("the route must take the mountain guide edge", usedGuide);
	}
}
