package gps.pathfinder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
 * The walk-out-of-the-wilderness scenario: global teleports unlock by wilderness band (the
 * abstract-hub-per-band machinery), so from deep wilderness the optimal route must WALK to the
 * first tile of a more permissive band and cast from there — no explicit "exit tile" is ever
 * chosen; it falls out of cost ordering. This pins:
 * <ul>
 * <li>the route walks (adjacent steps only) out of the level-30+ band and casts while still
 * inside the wilderness — at the earliest legal band, not after leaving entirely;</li>
 * <li>both the uninformed search and A* return the ORACLE's exact cost — the heuristic's field
 * deliberately ignores wilderness gates (underestimates are safe), and this proves that
 * blindness costs nothing in correctness.</li>
 * </ul>
 * Wilderness-origin escapes (obelisks, the lever) are excluded so the scenario genuinely
 * requires walking out; global teleports keep no origin and cannot be excluded that way.
 */
@RunWith(MockitoJUnitRunner.class)
public class WildernessEscapeTest
{
	// The Wilderness Agility Course entrance — deep wilderness (level ~52), verified walkable.
	private static final int DEEP_WILDERNESS = WorldPointUtil.packWorldPoint(3004, 3937, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);

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
	public void walksToTheFirstLegalBandThenTeleports()
	{
		assertTrue("premise: the start must lie in level-30+ wilderness",
			WildernessChecker.isInLevel30Wilderness(DEEP_WILDERNESS));

		PathfinderConfig cfg = new TestPathfinderConfig(client, config).copyForPlanning();
		cfg.refresh();

		// Exclude every method whose transport ORIGINATES inside the wilderness (obelisks, the
		// lever, boats...): those are dedicated deep-wildy escapes that would short-circuit the
		// scenario. What remains is walking plus origin-free global teleports.
		Set<TeleportMethod> wildernessOriginMethods = new HashSet<>();
		for (int origin : cfg.getTransportsPacked(false).keys())
		{
			if (!WildernessChecker.isInWilderness(origin))
			{
				continue;
			}
			for (Transport transport : cfg.getTransportsPacked(false).get(origin))
			{
				if (TeleportMethod.isMethodType(transport.getType()))
				{
					wildernessOriginMethods.add(TeleportMethod.fromTransport(transport));
				}
			}
		}
		cfg.rebuildAvailabilityWithExclusions(wildernessOriginMethods);

		Set<Integer> targets = Set.of(VARROCK);
		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(cfg, DEEP_WILDERNESS, targets);
		assertTrue("oracle must reach Varrock", oracle.reached);

		Pathfinder uninformed = new Pathfinder(cfg, DEEP_WILDERNESS, targets, null, Integer.MAX_VALUE, null);
		uninformed.run();
		assertEquals("uninformed search must match the oracle",
			oracle.cost, uninformed.getResult().getTotalCost());

		DistanceField field = DistanceField.build(cfg, targets);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(cfg, field);
		assertNotNull(heuristic);
		Pathfinder astar = new Pathfinder(cfg, DEEP_WILDERNESS, targets, null, Integer.MAX_VALUE, heuristic);
		astar.run();
		assertEquals("A* must match the oracle despite the field ignoring wilderness gates",
			oracle.cost, astar.getResult().getTotalCost());

		// The route's shape: a genuine walk (adjacent steps only) out of the 30+ band, then a
		// teleport jump cast INSIDE the wilderness below level 30 — the earliest legal band.
		List<PathStep> path = astar.getResult().getPathSteps();
		// A TELEPORT jump spans the map; agility obstacles along the way (the course's pipes and
		// ledges) also step >1 tile but only a handful — distinguish by span.
		int jumpIndex = -1;
		for (int i = 1; i < path.size(); i++)
		{
			if (WorldPointUtil.distanceBetween(
				path.get(i - 1).getPackedPosition(), path.get(i).getPackedPosition()) > 20)
			{
				jumpIndex = i;
				break;
			}
		}
		assertTrue("the route must contain a teleport jump", jumpIndex > 0);
		assertTrue("the walk out must be a real walk (dozens of steps), got " + jumpIndex,
			jumpIndex > 50);

		int castTile = path.get(jumpIndex - 1).getPackedPosition();
		assertTrue("the cast tile " + WorldPointUtil.unpackWorldPoint(castTile)
				+ " must be below the level-30 band (where teleports first unlock)",
			!WildernessChecker.isInLevel30Wilderness(castTile));
		assertTrue("the cast must happen while still INSIDE the wilderness — at the earliest legal"
				+ " band, not after walking all the way out",
			WildernessChecker.isInWilderness(castTile));
	}
}
