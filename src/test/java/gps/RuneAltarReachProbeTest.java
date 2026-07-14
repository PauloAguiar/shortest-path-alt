package gps;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;
import gps.pathfinder.CollisionMap;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.Pathfinder;
import gps.pathfinder.SplitFlagMap;
import gps.pathfinder.TestPathfinderConfig;

public class RuneAltarReachProbeTest
{
	@Test
	public void probe()
	{
		Assume.assumeTrue(Boolean.getBoolean("altarProbe"));
		Map<String, int[]> altars = new LinkedHashMap<>();
		altars.put("Air Altar", new int[]{2984, 3291, 0});
		altars.put("Mind Altar", new int[]{2981, 3513, 0});
		altars.put("Water Altar", new int[]{3184, 3164, 0});
		altars.put("Earth Altar", new int[]{3305, 3473, 0});
		altars.put("Fire Altar", new int[]{3312, 3254, 0});
		altars.put("Body Altar", new int[]{3052, 3444, 0});
		altars.put("Cosmic Altar", new int[]{2407, 4376, 0});
		altars.put("Law Altar", new int[]{2857, 3380, 0});
		altars.put("Nature Altar", new int[]{2868, 3018, 0});
		altars.put("Chaos Altar (Runecrafting)", new int[]{3059, 3590, 0});
		altars.put("Death Altar", new int[]{1859, 4638, 0});
		altars.put("Wrath Altar", new int[]{2445, 2824, 0});
		altars.put("Blood Altar", new int[]{3560, 9780, 0});
		altars.put("Astral Altar", new int[]{2158, 3864, 0});

		final Thread clientThread = Thread.currentThread();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
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
						return HybridPageFillTest.defaultValue(method.getReturnType());
				}
			});
		ShortestPathConfig config = Mockito.mock(ShortestPathConfig.class, invocation ->
		{
			String name = invocation.getMethod().getName();
			Class<?> type = invocation.getMethod().getReturnType();
			if (type == boolean.class)
			{
				return !"avoidWilderness".equals(name) && !"enableSeasonalTransports".equals(name);
			}
			if (type == int.class)
			{
				return "calculationCutoff".equals(name) ? 120 : 0;
			}
			if (type == TeleportationItem.class)
			{
				return TeleportationItem.ALL;
			}
			if (type == JewelleryBoxTier.class)
			{
				return JewelleryBoxTier.ORNATE;
			}
			return HybridPageFillTest.defaultValue(type);
		});
		CollisionMap map = new CollisionMap(SplitFlagMap.fromResources());
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		planning.refresh();
		int lumbridge = WorldPointUtil.packWorldPoint(3221, 3218, 0);
		for (Map.Entry<String, int[]> altar : altars.entrySet())
		{
			int[] p = altar.getValue();
			Set<Integer> ring = Destinations.walkableTargets(map, WorldPointUtil.packWorldPoint(p[0], p[1], p[2]));
			Pathfinder pathfinder = new Pathfinder(planning, lumbridge, ring);
			pathfinder.run();
			System.out.println((pathfinder.getResult().isReached() ? "REACHED   " : "UNREACHED ") + altar.getKey());
		}
	}
}
