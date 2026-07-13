package gps.pathfinder;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import gps.ShortestPathConfig;
import gps.TeleportMethod;
import gps.WorldPointUtil;
import gps.transport.Transport;

/**
 * The bounded (floor-limited) distance-field flood must be invisible to every search result: the
 * horizon only weakens the heuristic beyond the returnable cost band, never changes a cost. Each
 * scenario runs A* with the bounded field against {@link ReferenceDijkstra} — including with the
 * best method excluded (a chain#1-style search, whose floor rises above the build-time floor) —
 * and samples heuristic consistency across the flood frontier, where truncation could break the
 * triangle inequality if the unflooded fallback were wrong.
 */
@RunWith(MockitoJUnitRunner.class)
public class BoundedDistanceFieldTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final int SHILO_VILLAGE = WorldPointUtil.packWorldPoint(2852, 2954, 0);
	private static final int COST_MULTIPLE = 3;

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
		Pathfinder.validateSettleOrder = true;
	}

	@After
	public void after()
	{
		Pathfinder.validateSettleOrder = false;
	}

	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	private static void assertBoundedMatchesOracle(PathfinderConfig cfg, int start, Set<Integer> targets)
	{
		DistanceField bounded = DistanceField.build(cfg, targets, COST_MULTIPLE);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(cfg, bounded);
		assertNotNull(heuristic);

		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(cfg, start, targets);
		Pathfinder astar = new Pathfinder(cfg, start, targets, Integer.MAX_VALUE, heuristic);
		astar.run();

		assertEquals("bounded-field A* reached must match the oracle",
			oracle.reached, astar.getResult().isReached());
		if (oracle.reached)
		{
			assertEquals("bounded-field A* must return the oracle's minimal cost",
				oracle.cost, astar.getResult().getTotalCost());
		}
	}

	@Test
	public void boundedFieldSearchesMatchTheOracle()
	{
		PathfinderConfig cfg = everythingConfig();
		assertBoundedMatchesOracle(cfg, LUMBRIDGE, Set.of(BARROWS));
		assertBoundedMatchesOracle(cfg, GRAND_EXCHANGE, Set.of(SHILO_VILLAGE));
	}

	/**
	 * A chain-style search whose exclusions RAISE the per-search floor past the build-time floor the
	 * horizon was sized from — the exact regime where an unflooded tile's fallback must stay
	 * min(horizon, floor) and not the (now larger) floor alone. Excludes every method whose teleport
	 * lands inside the flooded zone (the floor-setters), the harshest version of the regime.
	 */
	@Test
	public void boundedFieldStaysOptimalWhenExclusionsRaiseTheFloor()
	{
		PathfinderConfig cfg = everythingConfig();
		Set<Integer> targets = Set.of(BARROWS);
		DistanceField bounded = DistanceField.build(cfg, targets, COST_MULTIPLE);
		assertTrue("the flood must actually be bounded", bounded.horizon() != Integer.MAX_VALUE);

		Set<TeleportMethod> excluded = new HashSet<>();
		for (boolean bankVisited : new boolean[]{false, true})
		{
			for (Transport teleport : cfg.getUsableTeleports(bankVisited))
			{
				if (teleport.getDestination() != WorldPointUtil.UNDEFINED
					&& bounded.distance(teleport.getDestination()) != DistanceField.UNREACHED
					&& TeleportMethod.isMethodType(teleport.getType()))
				{
					excluded.add(TeleportMethod.fromTransport(teleport));
				}
			}
		}
		assertTrue("some floor-setting teleports must exist to exclude", !excluded.isEmpty());
		cfg.rebuildAvailabilityWithExclusions(excluded);

		SearchHeuristic excludedHeuristic = SearchHeuristic.buildWithField(cfg, bounded);
		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(cfg, LUMBRIDGE, targets);
		Pathfinder astar = new Pathfinder(cfg, LUMBRIDGE, targets, Integer.MAX_VALUE, excludedHeuristic);
		astar.run();
		assertEquals(oracle.reached, astar.getResult().isReached());
		if (oracle.reached)
		{
			assertEquals("excluded-method search on the bounded field must stay optimal",
				oracle.cost, astar.getResult().getTotalCost());
		}
	}

	/**
	 * Consistency across the flood frontier: h(a) <= 1 + h(b) for walkable neighbours in a band
	 * spanning flooded and unflooded tiles. A wrong unflooded fallback (e.g. the raw floor) breaks
	 * the inequality exactly here.
	 */
	@Test
	public void heuristicStaysConsistentAcrossTheFrontier()
	{
		PathfinderConfig cfg = everythingConfig();
		DistanceField bounded = DistanceField.build(cfg, Set.of(BARROWS), COST_MULTIPLE);
		assertTrue("the flood must actually be bounded for this test to mean anything",
			bounded.horizon() != Integer.MAX_VALUE);
		SearchHeuristic h = SearchHeuristic.buildWithField(cfg, bounded);
		CollisionMap map = cfg.getMap();

		final int bx = 3566;
		final int by = 3291;
		// Wide box around the target: comfortably covers the horizon ring on all sides.
		final int radius = bounded.horizon() + 80;
		int violations = 0;
		int frontierPairs = 0;
		for (int x = bx - radius; x <= bx + radius; x++)
		{
			for (int y = by - radius; y <= by + radius; y++)
			{
				if (map.isBlocked(x, y, 0))
				{
					continue;
				}
				final int ha = h.of(WorldPointUtil.packWorldPoint(x, y, 0));
				final int[][] steps = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
				final boolean[] open = {map.w(x, y, 0), map.e(x, y, 0), map.s(x, y, 0), map.n(x, y, 0)};
				for (int i = 0; i < steps.length; i++)
				{
					if (!open[i])
					{
						continue;
					}
					final int b = WorldPointUtil.packWorldPoint(steps[i][0], steps[i][1], 0);
					final int hb = h.of(b);
					final boolean aFlooded = bounded.distance(WorldPointUtil.packWorldPoint(x, y, 0)) != DistanceField.UNREACHED;
					final boolean bFlooded = bounded.distance(b) != DistanceField.UNREACHED;
					if (aFlooded != bFlooded)
					{
						frontierPairs++;
					}
					if (ha > 1 + hb)
					{
						violations++;
					}
				}
			}
		}
		assertTrue("the sample must actually cross the frontier", frontierPairs > 0);
		assertEquals("consistency violations across the frontier", 0, violations);
	}
}
