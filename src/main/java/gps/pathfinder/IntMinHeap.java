package gps.pathfinder;

import java.util.Arrays;

/**
 * A binary min-heap of primitive {@code int} node ids ordered by {@link NodeGraph#orderCost} (compareCost, plus the heuristic in A* mode).
 * <p>
 * Replaces the {@code PriorityQueue<TransportNode>} pending queue in {@link Pathfinder} so transport
 * candidates are stored as int ids rather than boxed node objects. The ordering key is fixed when a
 * node is created (its differential cost never changes), so no decrease-key support is needed; the
 * pathfinder discards stale cheaper duplicates with its dequeue-time visited re-check. Single-threaded
 * (worker only), matching the queue it replaces.
 */
class IntMinHeap
{
	private final NodeGraph graph;
	private int[] heap;
	private int size;

	IntMinHeap(NodeGraph graph, int initialCapacity)
	{
		this.graph = graph;
		this.heap = new int[Math.max(1, initialCapacity)];
	}

	int size()
	{
		return size;
	}

	boolean isEmpty()
	{
		return size == 0;
	}

	/**
	 * @return the minimum-cost element, or {@link NodeGraph#NO_NODE} if empty.
	 */
	int peek()
	{
		return size == 0 ? NodeGraph.NO_NODE : heap[0];
	}

	void add(int id)
	{
		if (size == heap.length)
		{
			heap = Arrays.copyOf(heap, heap.length << 1);
		}
		heap[size] = id;
		siftUp(size);
		size++;
	}

	/**
	 * @return the removed minimum-cost element, or {@link NodeGraph#NO_NODE} if empty.
	 */
	int poll()
	{
		if (size == 0)
		{
			return NodeGraph.NO_NODE;
		}
		final int top = heap[0];
		size--;
		if (size > 0)
		{
			heap[0] = heap[size];
			siftDown(0);
		}
		return top;
	}

	void clear()
	{
		size = 0;
	}

	/**
	 * Ordering: orderCost first, node id (creation order) as the tie-break. The stability matters
	 * for path SHAPE: with a near-exact heuristic every tile on the optimal corridor shares the
	 * same f value, and an unstable heap pops those in arbitrary order — tiles get claimed by
	 * random parents and the reconstructed path zigzags between cardinal and diagonal steps. In
	 * creation order the claims mirror the FIFO search's (cardinals are generated first), which
	 * produces the long straight runs that match the game's own click-walk movement. It also makes
	 * searches fully deterministic.
	 */
	private boolean less(int a, int b)
	{
		final int costA = graph.orderCost(a);
		final int costB = graph.orderCost(b);
		return costA < costB || (costA == costB && a < b);
	}

	private void siftUp(int index)
	{
		final int id = heap[index];
		while (index > 0)
		{
			final int parent = (index - 1) >> 1;
			if (!less(id, heap[parent]))
			{
				break;
			}
			heap[index] = heap[parent];
			index = parent;
		}
		heap[index] = id;
	}

	private void siftDown(int index)
	{
		final int id = heap[index];
		final int half = size >> 1;
		while (index < half)
		{
			int child = (index << 1) + 1;
			final int right = child + 1;
			if (right < size && less(heap[right], heap[child]))
			{
				child = right;
			}
			if (!less(heap[child], id))
			{
				break;
			}
			heap[index] = heap[child];
			index = child;
		}
		heap[index] = id;
	}
}
