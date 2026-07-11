// Parses the plugin's real transport TSVs into the two edge kinds the engine
// cares about (a deliberate simplification of gps TransportLoader — planning
// mode: requirement columns ignored):
//   transports — fixed origin AND destination (boats, gliders, guides, ...)
//   teleports  — destination only, usable from anywhere (spells, items, ...)
// Rows with only one endpoint are network permutations (fairy rings, spirit
// trees): cross-producted within their file, mirroring the loader.
import { pack } from './collision.js';

const SKIP_FILES = new Set(['seasonal_transports.tsv']); // off by default in the plugin, hidden everywhere

function parseCoord(cell) {
	const m = /^(\d+)\s+(\d+)\s+(\d+)$/.exec((cell || '').trim());
	return m ? pack(+m[1], +m[2], +m[3]) : -1;
}

export function parseTransports(files) {
	const transports = [];
	const teleports = [];
	for (const { name, text } of files) {
		if (SKIP_FILES.has(name)) continue;
		const lines = text.split(/\r?\n/);
		if (!lines.length) continue;
		const header = lines[0].replace(/^#\s*/, '').split('\t').map(h => h.trim());
		const col = key => header.findIndex(h => h.toLowerCase().startsWith(key));
		const iOrigin = col('origin'), iDest = col('destination'), iDur = col('duration');
		const iMenu = col('menuoption'), iInfo = col('display info');
		const permOrigins = [], permDests = [];
		for (let li = 1; li < lines.length; li++) {
			const line = lines[li];
			if (!line || line.startsWith('#')) continue;
			const cells = line.split('\t');
			const origin = parseCoord(cells[iOrigin]);
			const dest = parseCoord(cells[iDest]);
			const dur = Math.max(1, parseInt(cells[iDur], 10) || 1);
			const cost = dur * 2; // CostUnits.fromTicks
			const info = (cells[iInfo] || '').trim();
			const menu = (cells[iMenu] || '').trim().replace(/\s+\d+$/, '');
			const label = info || menu || name;
			const group = name + '|' + label; // the "method" identity the exclusion chain uses
			if (origin >= 0 && dest >= 0) transports.push({ origin, dest, cost, label, group });
			else if (origin < 0 && dest >= 0) teleports.push({ dest, cost, label, group });
			else if (origin >= 0 && dest < 0) permOrigins.push({ origin, cost, label, group });
		}
		// Network permutation: origins x origins within the file (fairy rings, spirit trees,
		// gliders' hub files, ...) — skip near-identical endpoints like the loader does.
		const dests = permDests.length ? permDests : permOrigins.map(o => ({ dest: o.origin, label: o.label }));
		for (const o of permOrigins) {
			for (const d of dests) {
				if (o.origin === d.dest) continue;
				const dx = Math.abs(((o.origin >> 14) & 0x3fff) - ((d.dest >> 14) & 0x3fff));
				const dy = Math.abs((o.origin & 0x3fff) - (d.dest & 0x3fff));
				if (Math.max(dx, dy) <= 6) continue;
				transports.push({ origin: o.origin, dest: d.dest, cost: o.cost,
					label: o.label + ' → ' + d.label, group: name + '|network' });
			}
		}
	}
	return { transports, teleports };
}
