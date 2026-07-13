package gps.pathfinder;

import java.lang.reflect.Proxy;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.mockito.Mockito;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Shared setup for the search-pipeline benchmarks (heuristic build, search, availability, generate).
 * Builds an everything-mode planning config off the real collision map + transport data, and resolves
 * a few representative start/target queries of increasing difficulty.
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
	// Uncommon shapes (see UncommonPathShapesTest): boat-only island, deep wilderness both ways.
	private static final int ENTRANA = WorldPointUtil.packWorldPoint(2830, 3335, 0);
	private static final int DEEP_WILDERNESS = WorldPointUtil.packWorldPoint(3004, 3937, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);

	private BenchScenarios()
	{
	}

	static PathfinderConfig everythingConfig()
	{
		// ShortestPathConfig is read only a handful of times per refresh, so a mock is fine here.
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		PathfinderConfig config = new TestPathfinderConfig(realisticClient(), cfg).copyForPlanning();
		config.refresh();
		return config;
	}

	/**
	 * The classic (plugin) flow's config: NOT a planning copy, so possession/unlock gates apply.
	 * TestShortestPathConfig is a real implementing class, so every unoverridden setting resolves to
	 * the interface's default value — the plugin's real defaults (a Mockito CALLS_REAL_METHODS mock
	 * cannot invoke interface defaults on this Mockito version). With the proxy client's empty
	 * containers this models a default-config player with finished quests and no teleport items:
	 * walking plus quest-gated networks.
	 */
	static PathfinderConfig classicConfig()
	{
		gps.TestShortestPathConfig cfg = new gps.TestShortestPathConfig();
		cfg.setCalculationCutoffValue(120);
		PathfinderConfig config = new TestPathfinderConfig(realisticClient(), cfg);
		config.refresh();
		return config;
	}

	/**
	 * A real (non-Mockito) Client. refresh() makes thousands of getVarbitValue calls, and even a
	 * stubOnly Mockito mock allocates a transient invocation on every one — that alone made refresh
	 * measure ~15x slower and ~70x heavier than it is with a real client. A reflective Proxy returning
	 * game-state defaults is a far more representative (and still conservative) stand-in.
	 */
	private static Client realisticClient()
	{
		final Thread clientThread = Thread.currentThread();
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return defaultValue(method.getReturnType());
				}
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if (type == int.class || type == short.class || type == byte.class)
		{
			return 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == double.class)
		{
			return 0.0;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		return null;
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
			case "island":
			case "deep-wild":
				return LUMBRIDGE;
			case "wilderness-escape":
				return DEEP_WILDERNESS;
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
			case "island":
				return Set.of(ENTRANA);
			case "deep-wild":
				return Set.of(DEEP_WILDERNESS);
			case "wilderness-escape":
				return Set.of(VARROCK);
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}
}
