package gps.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import gps.WorldPointUtil;

/**
 * Pins down the cost formulas and flag bookkeeping of the structure-of-arrays node store so a
 * regression in any of the three distinct cost rules (tile walk, transport, abstract) fails fast.
 * The expected values mirror the maths of the old {@code Node}/{@code TransportNode} classes.
 */
public class NodeGraphTest
{
	@Test
	public void startNodeHasZeroCostAndNoPrevious()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));

		assertEquals(0, graph.cost(start));
		assertEquals(NodeGraph.NO_NODE, graph.previous(start));
		assertTrue(graph.isTile(start));
		assertFalse(graph.isAbstract(start));
		assertFalse(graph.isTransport(start));
		assertFalse(graph.bankVisited(start));
	}

	@Test
	public void tileFromTileAddsWalkingDistance()
	{
		NodeGraph graph = new NodeGraph(16);
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3205, 3203, 0);
		int start = graph.createStart(a);
		int tile = graph.createTile(b, start, false, 0);

		assertEquals(WorldPointUtil.distanceBetween(a, b), graph.cost(tile));
		assertEquals(start, graph.previous(tile));
		assertTrue(graph.isTile(tile));
		// With no heuristic the queue-ordering key is EXACTLY the accumulated cost.
		assertEquals(graph.cost(tile), graph.orderCost(tile));
	}

	@Test
	public void tileFromAbstractAddsNoWalkingDistance()
	{
		NodeGraph graph = new NodeGraph(16);
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3300, 3300, 0);
		int start = graph.createStart(a);
		int tileBeforeAbstract = graph.createTile(WorldPointUtil.packWorldPoint(3201, 3200, 0), start, false, 0);
		int abstractNode = graph.createAbstract(AbstractNodeKind.GLOBAL_TELEPORTS_NORMAL, tileBeforeAbstract, true, 0);
		int tileFromAbstract = graph.createTile(b, abstractNode, true, 0);

		// Reaching a tile from an abstract node adds no travel cost: it inherits the abstract cost.
		assertEquals(graph.cost(abstractNode), graph.cost(tileFromAbstract));
		assertTrue(graph.bankVisited(tileFromAbstract));
	}

	@Test
	public void tileEdgeAddsExtraCost()
	{
		NodeGraph graph = new NodeGraph(16);
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3205, 3203, 0);
		int start = graph.createStart(a);
		// The bank-pickup surcharge is charged on the edge that first enters the banked state.
		int tile = graph.createTile(b, start, true, 50);

		assertEquals(WorldPointUtil.distanceBetween(a, b) + 50, graph.cost(tile));
		assertTrue(graph.bankVisited(tile));
	}

	@Test
	public void abstractNodeAddsExtraCost()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));
		int tile = graph.createTile(WorldPointUtil.packWorldPoint(3210, 3200, 0), start, false, 0);
		int abstractNode = graph.createAbstract(AbstractNodeKind.GLOBAL_TELEPORTS_NORMAL, tile, true, 50);

		// A global teleport reached via a bank inherits the bank-pickup surcharge from the abstract node.
		assertEquals(graph.cost(tile) + 50, graph.cost(abstractNode));
		assertTrue(graph.bankVisited(abstractNode));
	}

	@Test
	public void abstractInheritsPreviousCostAndCarriesKind()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));
		int tile = graph.createTile(WorldPointUtil.packWorldPoint(3210, 3200, 0), start, false, 0);
		int abstractNode = graph.createAbstract(AbstractNodeKind.GLOBAL_TELEPORTS_OVER_30, tile, false, 0);

		assertEquals(graph.cost(tile), graph.cost(abstractNode));
		assertTrue(graph.isAbstract(abstractNode));
		assertFalse(graph.isTile(abstractNode));
		assertEquals(AbstractNodeKind.GLOBAL_TELEPORTS_OVER_30, graph.abstractKind(abstractNode));
		assertEquals(WorldPointUtil.UNDEFINED, graph.packedPosition(abstractNode));
	}

	@Test
	public void transportCostIsPreviousPlusTravelAndAdditionalWithNoDistance()
	{
		NodeGraph graph = new NodeGraph(16);
		int origin = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int destination = WorldPointUtil.packWorldPoint(2800, 3400, 0);
		int start = graph.createStart(origin);
		int prev = graph.createTile(WorldPointUtil.packWorldPoint(3201, 3200, 0), start, false, 0);

		int travelTime = 6;
		int additionalCost = 50;
		int transport = graph.createTransport(destination, prev, travelTime, additionalCost, false, true);

		// No walking-distance term for transports, unlike a walked tile.
		assertEquals(graph.cost(prev) + travelTime + additionalCost, graph.cost(transport));
		// The queue-ordering key is the cost and nothing else (no heuristic attached here): an
		// ordering-only term would break the first-settle-is-optimal invariant.
		assertEquals(graph.cost(transport), graph.orderCost(transport));
		assertTrue(graph.isTransport(transport));
		assertTrue(graph.isDelayedVisit(transport));
		assertTrue(graph.isTile(transport)); // transports are concrete tile destinations
	}

	@Test
	public void nonDelayedTransportHasNoDelayedFlag()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));
		int transport = graph.createTransport(WorldPointUtil.packWorldPoint(2800, 3400, 0), start, 6, 0, true, false);

		assertTrue(graph.isTransport(transport));
		assertFalse(graph.isDelayedVisit(transport));
		assertEquals(graph.cost(transport), graph.orderCost(transport));
		assertTrue(graph.bankVisited(transport));
	}

	@Test
	public void pathStepsSkipAbstractNodesInOrder()
	{
		NodeGraph graph = new NodeGraph(16);
		int a = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int b = WorldPointUtil.packWorldPoint(3201, 3200, 0);
		int c = WorldPointUtil.packWorldPoint(2800, 3400, 0);
		int start = graph.createStart(a);
		int tile = graph.createTile(b, start, false, 0);
		int abstractNode = graph.createAbstract(AbstractNodeKind.GLOBAL_TELEPORTS_NORMAL, tile, false, 0);
		int teleportDest = graph.createTransport(c, abstractNode, 6, 0, false, false);

		var steps = graph.getPathSteps(teleportDest);
		assertEquals(3, steps.size()); // start, tile, teleportDest (abstract is skipped)
		assertEquals(a, steps.get(0).getPackedPosition());
		assertEquals(b, steps.get(1).getPackedPosition());
		assertEquals(c, steps.get(2).getPackedPosition());

		assertEquals(c, graph.getClosestTilePosition(teleportDest));
		assertEquals(b, graph.getClosestTilePosition(abstractNode));
	}

	@Test
	public void releaseMakesChainWalksReturnEmpty()
	{
		NodeGraph graph = new NodeGraph(16);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));
		graph.release();

		assertTrue(graph.getPathSteps(start).isEmpty());
		assertEquals(WorldPointUtil.UNDEFINED, graph.getClosestTilePosition(start));
	}
}
