package shortestpath;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.TestPathfinderConfig;

/**
 * Reproduces the benchmark's "Nearest anvil from Varrock" scenario and prints the per-search
 * profile records — the diagnostic view for WHERE a multi-target generation's time goes (the
 * aggregate timing can't tell a few expensive searches from many cheap ones). Also guards the
 * records plumbing itself: every search of a generation must produce a record.
 */
@RunWith(MockitoJUnitRunner.class)
public class NearestSearchProfileTest
{
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3428, 0);

	@Mock
	Client client;
	@Mock
	ClientThread clientThread;
	@Mock
	ShortestPathConfig config;

	@Before
	public void before()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenAnswer(invocation -> Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
	}

	@Test
	public void nearestAnvilGenerationProfilesEverySearch() throws Exception
	{
		PathfinderConfig planning = new TestPathfinderConfig(client, config).copyForPlanning();
		AlternativeRoutesService service = new AlternativeRoutesService(clientThread, planning);

		Set<Integer> anvils = Destinations.tilesForCategory("anvil", null);
		assertFalse("Anvil destinations must load from the bundled resource", anvils.isEmpty());

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<RouteOption>> finalRoutes = new AtomicReference<>(List.of());
		long wallStart = System.nanoTime();
		service.generate(VARROCK, anvils, Set.of(), AlternativeRoutesMode.ALL_EVERYTHING, 10,
			(routes, catalog, unavailable, isDone) ->
			{
				if (isDone)
				{
					finalRoutes.set(routes);
					done.countDown();
				}
			});
		assertTrue("Generation should complete", done.await(120, TimeUnit.SECONDS));
		long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
		service.shutdown();

		List<AlternativeRoutesService.SearchRecord> records = service.getLastSearchRecords();
		long[] timing = service.getLastTimingSummary();
		System.out.println("=== Nearest anvil from Varrock: " + finalRoutes.get().size() + " routes, wall "
			+ wallMs + "ms, searchCpu " + timing[3] + "ms, " + timing[4] + " searches ===");
		System.out.printf("%-52s %7s %6s %8s %9s %11s %7s%n",
			"search", "cpuMs", "cost", "reached", "nodes", "transports", "capped");
		for (AlternativeRoutesService.SearchRecord r : records)
		{
			System.out.printf("%-52s %7d %6d %8s %9d %11d %7s  %s%n",
				r.label.length() > 52 ? r.label.substring(0, 52) : r.label,
				r.cpuMs, r.resultCost, r.reached, r.nodesChecked, r.transportsChecked, r.capped, r.termination);
		}

		assertFalse("Every search must produce a profile record", records.isEmpty());
		assertTrue("Record count must match the search count (" + records.size() + " vs " + timing[4] + ")",
			records.size() == timing[4]);

		// Regression guards for the perimeter-target fix: amenity destinations are object tiles that
		// aren't walkable — targeting only them meant NO search ever "reached", every search flooded
		// the entire map (~1.3M nodes), and the walk-cost cap never engaged. With the walkable
		// perimeter in the target set the searches must reach and stay bounded.
		assertTrue("The best route must actually reach a target",
			finalRoutes.get().stream().anyMatch(RouteOption::isReached));
		assertTrue("The walk search must reach the nearest anvil (it defines the cost cap)",
			records.stream().filter(r -> "walk".equals(r.label)).allMatch(r -> r.reached));
		assertTrue("Seed searches must run under the walk-cost cap",
			records.stream().filter(r -> r.label.startsWith("seed:")).allMatch(r -> r.capped));
		for (AlternativeRoutesService.SearchRecord r : records)
		{
			assertTrue("Search '" + r.label + "' explored " + r.nodesChecked
					+ " nodes — full-map flooding is back (fix regressed)",
				r.nodesChecked < 500_000);
		}
	}
}
