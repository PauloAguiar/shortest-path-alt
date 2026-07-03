package shortestpath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.TransportAvailability;
import shortestpath.transport.Transport;

/**
 * Generates up to {@link #MAX_ROUTES} alternative shortest paths to a target, each using a different
 * set of teleport/transport methods.
 * <p>
 * Strategy: run the planning-mode pathfinder, record the methods the best path used, exclude that
 * path's primary method, and search again. Repeating this yields successively-different routes in
 * increasing cost order. The user's own exclusions (methods they switched off in the panel) are
 * applied on top of every search.
 * <p>
 * Searches run on a dedicated background thread. Each search needs the planning config's transport
 * availability rebuilt for the current exclusion set, and {@link PathfinderConfig#refresh()} must run
 * on the client thread (it reads live game state), so the worker bounces each refresh onto the client
 * thread and waits for it before running the search off-thread.
 */
@Slf4j
public class AlternativeRoutesService
{
	public static final int MAX_ROUTES = 10;
	private static final int MAX_ROUTES_CAP = 50;
	private static final long CLIENT_THREAD_TIMEOUT_SECONDS = 10;

	/**
	 * Receives progressive updates for one generation: the catalog as soon as it's known, then the
	 * routes-so-far after each one is found, and a final call with {@code done == true}. Invoked on
	 * the worker thread; the caller marshals to the Swing EDT. Stale generations stop emitting.
	 */
	public interface ResultListener
	{
		void onUpdate(List<RouteOption> routes, List<TeleportMethod> catalog, boolean done);
	}

	private final ClientThread clientThread;
	private final PathfinderConfig planningConfig;
	private final ExecutorService executor;
	// Bumped on every generate()/cancel() so a stale in-flight generation discards its result.
	private final AtomicInteger generation = new AtomicInteger();

	public AlternativeRoutesService(ClientThread clientThread, PathfinderConfig planningConfig)
	{
		this.clientThread = clientThread;
		this.planningConfig = planningConfig;
		this.executor = Executors.newSingleThreadExecutor(
			new ThreadFactoryBuilder().setNameFormat("shortest-path-alts-%d").setDaemon(true).build());
	}

	/**
	 * Asynchronously computes the alternative routes, streaming progressive updates to {@code listener}
	 * (catalog first, then each route as it's found, then a final done update). Supersedes any in-flight
	 * generation.
	 */
	public void generate(int start, Set<Integer> targets, Set<TeleportMethod> userExclusions,
		AlternativeRoutesMode mode, int maxRoutes, ResultListener listener)
	{
		final int gen = generation.incrementAndGet();
		final Set<Integer> targetsCopy = new HashSet<>(targets);
		final Set<TeleportMethod> userExclusionsCopy = new HashSet<>(userExclusions);
		executor.submit(() ->
		{
			try
			{
				computeRoutes(gen, start, targetsCopy, userExclusionsCopy, mode, maxRoutes, listener);
			}
			catch (Exception e)
			{
				log.warn("Alternative route generation failed", e);
			}
		});
	}

	public void cancel()
	{
		generation.incrementAndGet();
	}

	public void shutdown()
	{
		executor.shutdownNow();
	}

	private void computeRoutes(int gen, int start, Set<Integer> targets,
		Set<TeleportMethod> userExclusions, AlternativeRoutesMode mode, int maxRoutes, ResultListener listener)
	{
		final int limit = Math.max(1, Math.min(maxRoutes, MAX_ROUTES_CAP));
		final Set<Integer> ends = new HashSet<>(targets);
		final Set<TeleportMethod> excluded = new HashSet<>(userExclusions);

		// First pass with NO exclusions: builds the full availability so we can read the complete method
		// catalog (including methods the user has excluded, so the panel can offer to toggle them back
		// on), and drops any targets the avoid-wilderness setting forbids.
		if (!refreshOnClientThread(Collections.emptySet(), ends, mode))
		{
			emit(gen, listener, List.of(), List.of(), true);
			return;
		}
		final List<TeleportMethod> catalog = new ArrayList<>(planningConfig.getMethodCatalog());
		if (ends.isEmpty())
		{
			emit(gen, listener, List.of(), catalog, true);
			return;
		}
		// Show the catalog right away while the routes are still computing.
		emit(gen, listener, List.of(), catalog, false);

		log.debug("[alt-routes] searching: start={}, target={}, mode={}, usableTeleports={}, catalog={}",
			WorldPointUtil.unpackWorldPoint(start),
			WorldPointUtil.unpackWorldPoint(ends.iterator().next()),
			mode, planningConfig.getUsableTeleports(false).length, catalog.size());

		final List<RouteOption> routes = new ArrayList<>();
		final Set<String> seenSignatures = new HashSet<>();

		for (int i = 0; i < limit; i++)
		{
			if (gen != generation.get())
			{
				return;
			}
			// Rebuild availability for the current exclusion set (the catalog pass above used none).
			if (!refreshOnClientThread(excluded, null, mode))
			{
				break;
			}

			Pathfinder pathfinder = new Pathfinder(planningConfig, start, ends);
			pathfinder.run();
			PathfinderResult result = pathfinder.getResult();
			List<PathStep> path = (result != null) ? result.getPathSteps() : List.of();
			if (result == null || path.isEmpty())
			{
				log.debug("[alt-routes] search #{} produced no path: result={}, reason={}",
					i, result == null ? "null" : "empty",
					result == null ? "n/a" : result.getTerminationReason());
				break;
			}

			boolean reached = result.isReached();
			// If the exact target is unreachable, Shortest Path shows the path to the closest tile; we do
			// the same for the FIRST route so the panel isn't empty. But once we already have reachable
			// routes, a search that only reaches the closest tile (because exclusions removed the method
			// that reached) isn't a useful alternative — stop.
			if (!reached && !routes.isEmpty())
			{
				log.debug("[alt-routes] search #{} unreachable after exclusions; stopping with {} route(s)", i, routes.size());
				break;
			}

			List<TeleportMethod> methods = methodsOf(path);

			// Distinct method-signature gate: if this route uses the same ordered methods as a previous
			// one, excluding more would only reshuffle, so stop.
			if (!seenSignatures.add(signature(methods)))
			{
				break;
			}
			routes.add(new RouteOption(path, methods, result.getTotalCost(), reached));
			// Stream the route we just found so the panel shows it immediately.
			emit(gen, listener, new ArrayList<>(routes), catalog, false);

			if (!reached)
			{
				log.debug("[alt-routes] search #{} reached only the closest tile (reason={}); stopping",
					i, result.getTerminationReason());
				break;
			}

			TeleportMethod primary = methods.isEmpty() ? null : methods.get(0);
			if (primary == null)
			{
				// Walk-only route: nothing left to exclude, so no further alternatives exist.
				break;
			}
			excluded.add(primary);
		}

		log.debug("[alt-routes] generated {} route(s)", routes.size());
		emit(gen, listener, new ArrayList<>(routes), catalog, true);
	}

	private void emit(int gen, ResultListener listener, List<RouteOption> routes,
		List<TeleportMethod> catalog, boolean done)
	{
		if (gen == generation.get())
		{
			listener.onUpdate(routes, catalog, done);
		}
	}

	/**
	 * Runs {@code setExcludedMethods + refresh} (and, when {@code endsToFilter} is non-null, target
	 * wilderness filtering) on the client thread and blocks the worker until it completes.
	 *
	 * @return false if the client thread did not run the task within the timeout.
	 */
	private boolean refreshOnClientThread(Set<TeleportMethod> excluded, Set<Integer> endsToFilter, AlternativeRoutesMode mode)
	{
		final Set<TeleportMethod> excludedSnapshot = new HashSet<>(excluded);
		final CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try
			{
				planningConfig.setPlanningMode(mode == AlternativeRoutesMode.ALL_TELEPORTS);
				planningConfig.setConsiderBank(mode == AlternativeRoutesMode.AVAILABLE_WITH_BANK);
				planningConfig.setExcludedMethods(excludedSnapshot);
				planningConfig.refresh();
				if (endsToFilter != null)
				{
					planningConfig.filterLocations(endsToFilter, true);
				}
			}
			finally
			{
				latch.countDown();
			}
		});
		try
		{
			if (!latch.await(CLIENT_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
			{
				log.warn("Timed out waiting for planning config refresh on client thread");
				return false;
			}
			return true;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Derives the ordered list of teleport/transport methods a path uses, by inspecting each edge for
	 * a method-type transport (anything beyond plain walking connectors) whose destination matches.
	 */
	private List<TeleportMethod> methodsOf(List<PathStep> path)
	{
		List<TeleportMethod> methods = new ArrayList<>();
		if (path == null)
		{
			return methods;
		}
		for (int i = 1; i < path.size(); i++)
		{
			PathStep from = path.get(i - 1);
			PathStep to = path.get(i);
			boolean bankVisited = from.isBankVisited() || to.isBankVisited();
			Transport chosen = matchMethodTransport(from.getPackedPosition(), to.getPackedPosition(), bankVisited);
			if (chosen != null)
			{
				methods.add(TeleportMethod.fromTransport(chosen));
			}
		}
		return methods;
	}

	private Transport matchMethodTransport(int origin, int destination, boolean bankVisited)
	{
		// Fixed-origin networks (fairy rings, spirit trees, boats, ...) keyed by their origin tile.
		Transport[] atOrigin = planningConfig.getTransportsPacked(bankVisited)
			.getOrDefault(origin, TransportAvailability.EMPTY_TRANSPORTS);
		for (Transport transport : atOrigin)
		{
			if (transport.getDestination() == destination && TeleportMethod.isMethodType(transport.getType()))
			{
				return transport;
			}
		}
		// Global teleports (spells/items/...): castable from anywhere, so the origin is wherever the
		// player stood; match purely on destination.
		for (Transport transport : planningConfig.getUsableTeleports(bankVisited))
		{
			if (transport.getDestination() == destination && TeleportMethod.isMethodType(transport.getType()))
			{
				return transport;
			}
		}
		return null;
	}

	private static String signature(List<TeleportMethod> methods)
	{
		if (methods.isEmpty())
		{
			return "<walk-only>";
		}
		StringBuilder sb = new StringBuilder();
		for (TeleportMethod method : methods)
		{
			sb.append(method.toString()).append('|');
		}
		return sb.toString();
	}
}
