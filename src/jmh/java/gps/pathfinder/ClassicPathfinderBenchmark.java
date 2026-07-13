package gps.pathfinder;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark for the CLASSIC pathfinder — the plugin's original single-path flow
 * ({@code ShortestPathPlugin.restartPathfinding}): an uninformed (no distance field, no heuristic),
 * uncapped search. It still runs on every target change as the fallback/state-holder alongside the
 * alternative-routes generation, so this measures exactly what removing that redundant search would
 * save per target set.
 * <p>
 * The {@code availability} axis brackets reality: {@code everything} is the planning-mode superset
 * (every teleport usable — the alt-routes engine's view, and the worst case for transport fan-out);
 * {@code classic-defaults} is the true classic view — a possession-checked config with the plugin's
 * default settings, finished quests and empty containers (walking plus quest-gated networks).
 * <p>
 * Run: {@code ./gradlew -Pjmh jmh --args='ClassicPathfinderBenchmark -prof gc'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ClassicPathfinderBenchmark
{
	@Param({"lumbridge-barrows", "ge-shilo", "capture"})
	public String scenario;

	@Param({"everything", "classic-defaults"})
	public String availability;

	private PathfinderConfig config;
	private int start;
	private Set<Integer> targets;

	@Setup(Level.Trial)
	public void setup()
	{
		config = "everything".equals(availability)
			? BenchScenarios.everythingConfig()
			: BenchScenarios.classicConfig();
		start = BenchScenarios.start(scenario);
		targets = BenchScenarios.targets(scenario);
	}

	@Benchmark
	public PathfinderResult search()
	{
		// The classic flow's configuration: costCap = MAX_VALUE, heuristic = null (uninformed).
		Pathfinder pathfinder = new Pathfinder(config, start, targets);
		pathfinder.run();
		return pathfinder.getResult();
	}
}
