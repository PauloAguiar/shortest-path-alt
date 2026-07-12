package gps.pathfinder;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Shared setup for the search-pipeline benchmarks (heuristic build, search). Builds an
 * everything-mode planning config off the real collision map + transport data, and resolves a few
 * representative start/target queries of increasing difficulty.
 */
final class BenchScenarios
{
	// Cross-country, largely walking with a few transports.
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	// Needs a real teleport/boat leg to another region.
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final int SHILO_VILLAGE = WorldPointUtil.packWorldPoint(2852, 2954, 0);
	// The capture query: Lumbridge to the Varlamore Hunter Guild — a cross-continent multi-teleport.
	private static final int CAPTURE_START = WorldPointUtil.packWorldPoint(3219, 3219, 0);
	private static final Set<Integer> CAPTURE_TARGETS = Set.of(
		WorldPointUtil.packWorldPoint(1556, 3046, 0),
		WorldPointUtil.packWorldPoint(1555, 3045, 0),
		WorldPointUtil.packWorldPoint(1555, 3046, 0),
		WorldPointUtil.packWorldPoint(1554, 3046, 0));

	private BenchScenarios()
	{
	}

	static PathfinderConfig everythingConfig()
	{
		Client client = Mockito.mock(Client.class);
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class);
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(client.getClientThread()).thenReturn(Thread.currentThread());
		Mockito.when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		return config;
	}

	static int start(String scenario)
	{
		switch (scenario)
		{
			case "lumbridge-barrows":
				return LUMBRIDGE;
			case "ge-shilo":
				return GRAND_EXCHANGE;
			case "capture":
				return CAPTURE_START;
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}

	static Set<Integer> targets(String scenario)
	{
		switch (scenario)
		{
			case "lumbridge-barrows":
				return Set.of(BARROWS);
			case "ge-shilo":
				return Set.of(SHILO_VILLAGE);
			case "capture":
				return CAPTURE_TARGETS;
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}
}
