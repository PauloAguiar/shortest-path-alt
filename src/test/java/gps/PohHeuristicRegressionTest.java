package gps;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;

public class PohHeuristicRegressionTest
{
	private AlternativeRoutesService service;
	private PathfinderConfig planningConfig;

	@Before
	public void before()
	{
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
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class, Mockito.withSettings().stubOnly());
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(cfg.usePoh()).thenReturn(true);
		Mockito.when(cfg.useTeleportationPortalsPoh()).thenReturn(true);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		planningConfig = config;
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(ct, config);
	}

	@org.junit.After
	public void after()
	{
		service.shutdown();
	}

	/**
	 * User capture pair (20260713-232224/232231): from Keldagrim, the four "enter the POH, take the
	 * Lassar Portal" routes (cost 29) only appeared AFTER "+", jumping above the first page's 67+
	 * routes. The distance field's reverse transport index credited POH portals to their interior
	 * origin instead of the remapped landing key, leaving the landing unflooded — the heuristic then
	 * overestimated inside the house (inadmissible), so house-mediated routes settled late. The
	 * first page must now carry them.
	 */
	@Test
	public void houseMediatedRoutesAreOnTheFirstPage() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(2907, 10172, 0),
			Set.of(WorldPointUtil.packWorldPoint(3009, 3487, 0)), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue(latch.await(60, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertNotNull(routes);
		int pohPortalRoutesAtBestTier = 0;
		for (RouteOption route : routes)
		{
			boolean viaPohPortal = route.getMethods().stream()
				.anyMatch(m -> gps.transport.TransportType.TELEPORTATION_PORTAL_POH.equals(m.getType()));
			if (viaPohPortal && route.getTotalCost() <= routes.get(0).getTotalCost() + 2)
			{
				pohPortalRoutesAtBestTier++;
			}
		}
		assertTrue("the first page must include the house->Lassar Portal routes at the best cost tier"
			+ " (found " + pohPortalRoutesAtBestTier + ")", pohPortalRoutesAtBestTier >= 3);
	}

	/**
	 * Route 0 itself was vulnerable, not just the lower ranks: for a player without the direct
	 * Lassar teleports, the TRUE optimum is the house route (29) — pre-fix, the inadmissible
	 * heuristic made the first search return Mind Altar (67) as the top route instead. Excluding
	 * the direct teleports simulates that player.
	 */
	@Test
	public void routeZeroIsTheHouseRouteWhenDirectTeleportsAreMissing() throws Exception
	{
		Set<TeleportMethod> exclusions = new java.util.HashSet<>();
		for (TeleportMethod method : planningConfig.getMethodCatalog())
		{
			if (method.label().equals("Lassar tablet") || method.label().equals("Lassar Teleport"))
			{
				exclusions.add(method);
			}
		}
		assertTrue("both direct Lassar methods must exist in the catalog", exclusions.size() == 2);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(WorldPointUtil.packWorldPoint(2907, 10172, 0),
			Set.of(WorldPointUtil.packWorldPoint(3009, 3487, 0)), exclusions,
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue(latch.await(60, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertNotNull(routes);
		assertTrue("routes must be found", !routes.isEmpty());
		RouteOption best = routes.get(0);
		boolean viaPohPortal = best.getMethods().stream()
			.anyMatch(m -> gps.transport.TransportType.TELEPORTATION_PORTAL_POH.equals(m.getType()));
		assertTrue("route 0 must be the house-mediated optimum (cost " + best.getTotalCost()
			+ ", methods " + best.getMethods() + ")", viaPohPortal && best.getTotalCost() <= 30);
	}
}
