// The pipeline on the REAL map — same architecture as the plugin:
//   1. FIELD  (DistanceField): reverse Dijkstra flood from the goal — walking
//      symmetric, origin-bound transports reversed exactly, teleports NOT flooded.
//   2. FLOOR  (SearchHeuristic): min over teleports of (cast + field(landing)),
//      recomputed per search — excluding methods RAISES it.
//   3. SEARCH (Pathfinder): forward A*, f = g + min(field, floor), teleport
//      landings enqueued from the start (the degenerate one-band hub).
//   4. CHAIN  (AlternativeRoutesService): route -> exclude its primary method ->
//      search again, until walking wins.
// Events are batched (the field settles hundreds of thousands of tiles) so the
// scrubber stays responsive.
import { MinHeap } from './heap.js';
import { unX, unY } from './collision.js';

const FIELD_BATCH = 96;
const SEARCH_BATCH = 24;
const FIELD_MARGIN = 1.3;      // flood until d > d(start) * margin + 60 (visual completeness)
const MAX_SETTLES = 900_000;   // runaway guard

export function buildTrace({ col, data, start, goal, useHeuristic = true, maxRoutes = 4 }) {
	const ev = [];
	const scratch = [];

	// destination-keyed reverse index of origin-bound transports (exact reversal)
	const byDest = new Map(), byOrigin = new Map();
	for (const t of data.transports) {
		if (!byDest.has(t.dest)) byDest.set(t.dest, []);
		byDest.get(t.dest).push(t);
		if (!byOrigin.has(t.origin)) byOrigin.set(t.origin, []);
		byOrigin.get(t.origin).push(t);
	}

	// ── Phase 1: the field ──────────────────────────────────────────────
	ev.push({ t: 'phase', phase: 'FIELD', note: 'reverse Dijkstra flood from the goal (DistanceField)' });
	const field = new Map();
	{
		const heap = new MinHeap();
		heap.push([0, goal]);
		field.set(goal, 0);
		let batch = [], dStart = Infinity, settles = 0;
		while (heap.size && settles < MAX_SETTLES) {
			const [d, p] = heap.pop();
			if (d > (field.get(p) ?? Infinity)) continue;
			if (d > dStart * FIELD_MARGIN + 60) break;
			settles++;
			batch.push(p, d);
			if (batch.length >= FIELD_BATCH * 2) { ev.push({ t: 'fieldBatch', cells: batch }); batch = []; }
			if (p === start && dStart === Infinity) {
				dStart = d;
				ev.push({ t: 'log', msg: `field: reached the start at ${d} — flooding a margin further, then stopping` });
			}
			for (const q of col.walkNeighbors(p, scratch)) {
				const nd = d + 1;
				if (nd < (field.get(q) ?? Infinity)) { field.set(q, nd); heap.push([nd, q]); }
			}
			const rev = byDest.get(p);
			if (rev) {
				for (const tr of rev) {
					const nd = d + tr.cost;
					if (nd < (field.get(tr.origin) ?? Infinity)) {
						field.set(tr.origin, nd);
						heap.push([nd, tr.origin]);
						ev.push({ t: 'log', msg: `field: reversed "${tr.label}" — its origin relaxed to ${nd}` });
					}
				}
			}
		}
		if (batch.length) ev.push({ t: 'fieldBatch', cells: batch });
		ev.push({ t: 'log', msg: `field: ${field.size} tiles valued` });

		// patchBlockedLandings (the bug the Java engine fixed): a teleport/transport landing on a
		// BLOCKED tile can't be reached by the walking flood, so it would take the floor — an
		// OVERESTIMATE of its true remaining cost, burying cheap routes through it. Value such
		// landings from their step-off neighbours (a real forward edge, so the bound holds).
		let patched = 0;
		const landings = new Set(data.teleports.map(m => m.dest));
		for (const tr of data.transports) landings.add(tr.dest);
		for (const p of landings) {
			if (field.has(p)) continue;
			const x = unX(p), y = unY(p), z = (p >>> 28) & 0x3;
			if (!col.isBlocked(x, y, z)) continue;
			let best = Infinity;
			for (const q of col.walkNeighbors(p, scratch)) {
				const fq = field.get(q);
				if (fq !== undefined && fq + 1 < best) best = fq + 1;
			}
			if (best !== Infinity) { field.set(p, best); patched++; }
		}
		if (patched) ev.push({ t: 'log', msg: `field: patched ${patched} blocked landings from their step-off neighbours (admissibility!)` });
	}

	// ── Phases 2-4: floor + forward searches + exclusion chain ──────────
	const excluded = new Set();
	const routes = [];
	for (let k = 0; k < maxRoutes; k++) {
		const teles = data.teleports.filter(m => !excluded.has(m.group));
		let floor = Infinity;
		for (const m of teles) {
			const fd = field.get(m.dest);
			if (fd !== undefined) floor = Math.min(floor, m.cost + fd);
		}
		ev.push({ t: 'phase', phase: `FLOOR #${k}`, note: floor === Infinity
			? 'no teleports reach the flooded area — floor = ∞'
			: `floor = min(cast + field(landing)) = ${floor}${excluded.size ? ` (after excluding: ${[...excluded].map(g => g.split('|')[1] || g).join(', ')})` : ''}` });
		const h = p => useHeuristic ? Math.min(field.get(p) ?? floor, floor) : 0;

		ev.push({ t: 'phase', phase: `SEARCH #${k}`, note: `forward A*, h = ${useHeuristic ? 'min(field, floor)' : '0 (Dijkstra)'}` });
		const g = new Map(), parent = new Map(), parentEdge = new Map();
		const settled = new Set();
		const heap = new MinHeap();
		const push = (p, gp, via, edge) => {
			if (gp >= (g.get(p) ?? Infinity)) return;
			g.set(p, gp);
			parent.set(p, via);
			if (edge) parentEdge.set(p, edge); else parentEdge.delete(p);
			heap.push([gp + h(p), gp, p]);
		};
		push(start, 0, -1, null);
		for (const m of teles) push(m.dest, m.cost, start, m);
		ev.push({ t: 'log', msg: `search #${k}: ${teles.length} teleport landings queued from the start (usable from anywhere)` });

		let goalCost = -1, batch = [], settles = 0;
		while (heap.size && settles < MAX_SETTLES) {
			const [f, gp, p] = heap.pop();
			if (settled.has(p) || gp > (g.get(p) ?? Infinity)) continue;
			settled.add(p);
			settles++;
			batch.push(p);
			if (batch.length >= SEARCH_BATCH) {
				ev.push({ t: 'settleBatch', k, cells: batch, pqTop: heap.top(12).map(x => ({ f: x[0], g: x[1], cell: x[2] })) });
				batch = [];
			}
			if (p === goal) {
				goalCost = gp;
				ev.push({ t: 'log', msg: `search #${k}: GOAL POPPED at f = g = ${gp} after ${settles} settles — every cheaper f is settled, so this is optimal` });
				break;
			}
			for (const q of col.walkNeighbors(p, scratch)) push(q, gp + 1, p, null);
			const out = byOrigin.get(p);
			if (out) {
				for (const tr of out) if (!excluded.has(tr.group)) push(tr.dest, gp + tr.cost, p, tr);
			}
		}
		if (batch.length) ev.push({ t: 'settleBatch', k, cells: batch, pqTop: [] });
		if (goalCost < 0) { ev.push({ t: 'log', msg: `search #${k}: goal not reached — chain ends` }); break; }

		const path = [];
		let cur = goal, primary = null;
		while (cur !== -1 && cur !== undefined) {
			path.push(cur);
			const edge = parentEdge.get(cur);
			if (edge) primary = edge;      // walking backwards: the last one seen = the first edge from the start
			cur = parent.get(cur);
		}
		path.reverse();
		const label = primary ? primary.label : 'Walk only';
		routes.push({ cost: goalCost, path, method: label });
		ev.push({ t: 'route', k, cost: goalCost, path, method: label, settles });
		if (!primary) { ev.push({ t: 'log', msg: 'chain: walk-only route — nothing cheaper than walking remains' }); break; }
		excluded.add(primary.group);
		ev.push({ t: 'log', msg: `chain: excluding "${label}" and searching again (same field, recomputed floor)` });
	}
	ev.push({ t: 'phase', phase: 'DONE', note: `${routes.length} routes, cheapest first — the panel's list` });
	return { ev, routes };
}

// ── Replay: fold events[0..step] into a drawable state ─────────────────
export function makeState() {
	return { phase: '', note: '', field: new Map(), settled: new Set(), searchK: -1,
		pqTop: [], routes: [], log: [], lastCell: -1 };
}

export function applyEvent(s, e) {
	switch (e.t) {
		case 'phase':
			s.phase = e.phase; s.note = e.note;
			if (e.phase.startsWith('SEARCH')) { s.settled = new Set(); s.pqTop = []; }
			s.log.push(`── ${e.phase}: ${e.note}`);
			break;
		case 'fieldBatch':
			for (let i = 0; i < e.cells.length; i += 2) s.field.set(e.cells[i], e.cells[i + 1]);
			s.lastCell = e.cells[e.cells.length - 2];
			break;
		case 'settleBatch':
			for (const c of e.cells) s.settled.add(c);
			s.lastCell = e.cells[e.cells.length - 1];
			if (e.pqTop && e.pqTop.length) s.pqTop = e.pqTop;
			s.searchK = e.k;
			break;
		case 'route':
			s.routes.push(e);
			s.log.push(`★ route ${s.routes.length}: ${e.method}, cost ${e.cost} (${e.settles} settles)`);
			break;
		case 'log': s.log.push(e.msg); break;
	}
	return s;
}
