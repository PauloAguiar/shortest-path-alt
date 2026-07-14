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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import gps.pathfinder.PathfinderConfig;
import gps.pathfinder.TestPathfinderConfig;
import gps.transport.TransportType;

/**
 * A generated balloon route must carry its flight as a route METHOD (so the route card lists it and
 * it can be excluded), and the method must name the vehicle — the data label is just the
 * destination ("Crafting Guild"), which read as a place, not a travel method, on the card and in
 * the "Use Crafting Guild" direction step.
 */
@RunWith(MockitoJUnitRunner.class)
public class BalloonRouteLabelTest
{
	private static final int TAVERLEY_BASKET = WorldPointUtil.packWorldPoint(2938, 3424, 0);
	private static final int CRAFTING_GUILD_LANDING = WorldPointUtil.packWorldPoint(2924, 3301, 0);

	private AlternativeRoutesService service;

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
		Mockito.when(cfg.useHotAirBalloons()).thenReturn(true);
		PathfinderConfig config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();
		ClientThread ct = Mockito.mock(ClientThread.class, Mockito.withSettings().stubOnly());
		Mockito.doAnswer(i ->
		{
			((Runnable) i.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(Mockito.any(Runnable.class));
		service = new AlternativeRoutesService(ct, config);
	}

	@After
	public void after()
	{
		service.shutdown();
	}

	@Test
	public void balloonFlightIsARecordedMethodNamingTheVehicle() throws Exception
	{
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(TAVERLEY_BASKET, Set.of(CRAFTING_GUILD_LANDING), Set.of(),
			AlternativeRoutesMode.ALL_EVERYTHING, 10, 3, false,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		assertTrue("generation must finish", latch.await(60, TimeUnit.SECONDS));
		List<RouteOption> routes = out.get();
		assertNotNull(routes);

		TeleportMethod flight = null;
		for (RouteOption route : routes)
		{
			for (TeleportMethod method : route.getMethods())
			{
				if (TransportType.HOT_AIR_BALLOON.equals(method.getType()))
				{
					flight = method;
					break;
				}
			}
		}
		assertNotNull("standing at the Taverley basket, a route must use the balloon flight", flight);
		org.junit.Assert.assertEquals("the route label must name the vehicle, not just the place",
			"Balloon to Crafting Guild", flight.routeLabel());
		org.junit.Assert.assertEquals("the catalog label stays the bare destination (it sits under"
			+ " its category header)", "Crafting Guild", flight.label());
	}
}
