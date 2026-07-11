// Headless check that the real-map engine works: loads the copied assets from
// public/ and runs two presets, printing the routes. Not a test — a dev aid.
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { loadCollision, pack } from '../src/collision.js';
import { parseTransports } from '../src/transports.js';
import { buildTrace } from '../src/engine.js';

const here = dirname(fileURLToPath(import.meta.url));
const pub = join(here, '..', 'public');

const col = await loadCollision(readFileSync(join(pub, 'collision-map.zip')));
const files = readdirSync(join(pub, 'transports')).filter(f => f.endsWith('.tsv'))
	.map(name => ({ name, text: readFileSync(join(pub, 'transports', name), 'utf-8') }));
const data = parseTransports(files);
console.log(`assets: ${data.transports.length} transports, ${data.teleports.length} teleports`);

const presets = [
	['Lumbridge -> Varrock', [3222, 3218], [3213, 3424]],
	['GE -> Shilo Village', [3164, 3487], [2852, 2954]],
	['Ardougne dock -> Brimhaven', [2683, 3271], [2772, 3234]],
];
for (const [name, s, g] of presets) {
	const t0 = Date.now();
	const t = buildTrace({ col, data, start: pack(s[0], s[1], 0), goal: pack(g[0], g[1], 0), useHeuristic: true });
	const d = buildTrace({ col, data, start: pack(s[0], s[1], 0), goal: pack(g[0], g[1], 0), useHeuristic: false });
	const costs = t.routes.map(r => r.cost).join(',');
	const dCosts = d.routes.map(r => r.cost).join(',');
	console.log(`\n${name}  (${Date.now() - t0} ms, ${t.ev.length} events)`);
	t.routes.forEach((r, i) => console.log(`  route ${i + 1}: ${r.method} = ${r.cost} (path ${r.path.length})`));
	console.log(`  optimality: heuristic costs [${costs}] ${costs === dCosts ? '==' : '!='} dijkstra costs [${dCosts}]`);
}
