package gps.pathfinder;

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
import gps.WorldPointUtil;
import gps.transport.Transport;
import gps.transport.TransportType;

/**
 * The search optimality contract, checked against {@link ReferenceDijkstra} (textbook Dijkstra
 * over the same edge expansion — the oracle): every production search, informed or not, must
 * return EXACTLY the oracle's minimal cost. This is what guarantees the panel's route 0 is the
 * true optimum regardless of the route count.
 * <p>
 * Battery notes: the Varlamore scenario reproduces a user capture where the primary search
 * returned a 177-cost route while a 101-cost route existed (surfaced only by the seed pass at a
 * higher route limit). The shared-destination scenario forces the quetzal whistle's differential
 * cost into play — with the differential in the ordering key only, the cheaper arrival loses the
 * settle race to a costlier competitor and is permanently discarded.
 */
@RunWith(MockitoJUnitRunner.class)
public class SearchOptimalityTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final int SHILO_VILLAGE = WorldPointUtil.packWorldPoint(2852, 2954, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int FALADOR = WorldPointUtil.packWorldPoint(2965, 3380, 0);
	private static final int DRAYNOR = WorldPointUtil.packWorldPoint(3093, 3245, 0);

	// The user capture (gps-capture-20260709-162344): Lumbridge courtyard to the Hunter Guild
	// entrance tiles in Varlamore.
	private static final int CAPTURE_START = WorldPointUtil.packWorldPoint(3219, 3219, 0);
	private static final Set<Integer> CAPTURE_TARGETS = Set.of(
		WorldPointUtil.packWorldPoint(1556, 3046, 0),
		WorldPointUtil.packWorldPoint(1555, 3045, 0),
		WorldPointUtil.packWorldPoint(1555, 3046, 0),
		WorldPointUtil.packWorldPoint(1554, 3046, 0));

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

	/** An Everything-mode planning copy (possession and unlocks bypassed), refreshed. */
	private PathfinderConfig everythingConfig()
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		return planning;
	}

	/**
	 * Runs the oracle plus both production searches (uninformed, and A* with the distance-field
	 * heuristic) and asserts every one returns the oracle's minimal cost.
	 */
	private static void assertOptimal(PathfinderConfig config, int start, Set<Integer> targets)
	{
		ReferenceDijkstra.Result oracle = ReferenceDijkstra.search(config, start, targets);

		Pathfinder uninformed = new Pathfinder(config, start, targets, null, Integer.MAX_VALUE, null);
		uninformed.run();

		DistanceField field = DistanceField.build(config, targets);
		SearchHeuristic heuristic = SearchHeuristic.buildWithField(config, field);
		assertNotNull("field heuristic must build", heuristic);
		Pathfinder astar = new Pathfinder(config, start, targets, null, Integer.MAX_VALUE, heuristic);
		astar.run();

		assertEquals("uninformed reached must match the oracle",
			oracle.reached, uninformed.getResult().isReached());
		assertEquals("A* reached must match the oracle",
			oracle.reached, astar.getResult().isReached());
		if (oracle.reached)
		{
			assertEquals("uninformed search must return the minimal cost",
				oracle.cost, uninformed.getResult().getTotalCost());
			assertEquals("A* search must return the minimal cost",
				oracle.cost, astar.getResult().getTotalCost());
		}
	}

	@Test
	public void captureQueryLumbridgeToHunterGuildIsOptimal()
	{
		assertOptimal(everythingConfig(), CAPTURE_START, CAPTURE_TARGETS);
	}

	@Test
	public void lumbridgeToBarrowsIsOptimal()
	{
		assertOptimal(everythingConfig(), LUMBRIDGE, Set.of(BARROWS));
	}

	@Test
	public void grandExchangeToShiloIsOptimal()
	{
		assertOptimal(everythingConfig(), GRAND_EXCHANGE, Set.of(SHILO_VILLAGE));
	}

	@Test
	public void multiTargetIsOptimal()
	{
		assertOptimal(everythingConfig(), LUMBRIDGE, Set.of(VARROCK, FALADOR, DRAYNOR));
	}

	/**
	 * Shared-destination competition under a non-zero whistle differential: the quetzal whistle
	 * teleport and a quetzal flight land on the SAME tile. Starting at the flight's origin makes
	 * the flight arrival cheap-and-early while the differential inflates the whistle's queue
	 * position — the exact configuration where an ordering-only cost term lets a costlier arrival
	 * settle the destination first and the cheaper one be discarded.
	 */
	@Test
	public void whistleDifferentialSharedDestinationIsOptimal()
	{
		when(config.costQuetzalWhistle()).thenReturn(100);
		PathfinderConfig cfg = everythingConfig();

		// Derive a concrete flight from the loaded data instead of hardcoding coordinates.
		Transport flight = null;
		for (int origin : cfg.getTransportsPacked(false).keys())
		{
			for (Transport transport : cfg.getTransportsPacked(false).get(origin))
			{
				if (TransportType.QUETZAL.equals(transport.getType())
					&& transport.getOrigin() != WorldPointUtil.UNDEFINED
					&& transport.getDestination() != WorldPointUtil.UNDEFINED)
				{
					flight = transport;
					break;
				}
			}
			if (flight != null)
			{
				break;
			}
		}
		assertNotNull("a quetzal flight must exist in the transport data", flight);
		assertTrue("the whistle must be usable so the shared-destination race exists",
			hasWhistle(cfg));

		assertOptimal(cfg, flight.getOrigin(), Set.of(flight.getDestination()));
	}

	private static boolean hasWhistle(PathfinderConfig cfg)
	{
		for (Transport teleport : cfg.getUsableTeleports(false))
		{
			if (TransportType.QUETZAL_WHISTLE.equals(teleport.getType()))
			{
				return true;
			}
		}
		return false;
	}
}
