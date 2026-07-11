// The real collision map, decoded from the plugin's collision-map.zip.
// Format (same as gps SplitFlagMap / the dashboard's overlay): one zip entry per
// 64x64 region named "<rx>_<ry>", each a java.util.BitSet payload with two
// edge-passability flags per tile per plane:
//   flag 0 (NORTH) — edge (x,y)->(x,y+1) walkable
//   flag 1 (EAST)  — edge (x,y)->(x+1,y) walkable
//   index = (z*64*64 + (y-minY)*64 + (x-minX))*2 + flag
import JSZip from 'jszip';

const REGION = 64;

export const pack = (x, y, z) => (z << 28) | (x << 14) | y;
export const unX = p => (p >> 14) & 0x3fff;
export const unY = p => p & 0x3fff;
export const unZ = p => (p >>> 28) & 0x3;

export async function loadCollision(arrayBuffer) {
	const zip = await JSZip.loadAsync(arrayBuffer);
	const regions = new Map();
	await Promise.all(Object.keys(zip.files).map(async name => {
		regions.set(name, await zip.file(name).async('uint8array'));
	}));

	function edge(x, y, z, flag) {
		const rx = Math.floor(x / REGION), ry = Math.floor(y / REGION);
		const bytes = regions.get(rx + '_' + ry);
		if (!bytes) return false;
		const idx = ((z * REGION * REGION) + ((y - ry * REGION) * REGION) + (x - rx * REGION)) * 2 + flag;
		const byteIdx = idx >>> 3;
		if (byteIdx >= bytes.length) return false;
		return (bytes[byteIdx] & (1 << (idx & 7))) !== 0;
	}

	const n = (x, y, z) => edge(x, y, z, 0);
	const e = (x, y, z) => edge(x, y, z, 1);
	const s = (x, y, z) => n(x, y - 1, z);
	const w = (x, y, z) => e(x - 1, y, z);
	const isBlocked = (x, y, z) => !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z);

	/** Walking neighbours of a packed tile — a port of CollisionMap.getTileNeighbors'
	 *  walking branch, including the step-off rules for blocked tiles (teleport landings). */
	function walkNeighbors(p, out) {
		const x = unX(p), y = unY(p), z = unZ(p);
		out.length = 0;
		let tW, tE, tS, tN, tSW, tSE, tNW, tNE;
		if (isBlocked(x, y, z)) {
			const bW = isBlocked(x - 1, y, z), bE = isBlocked(x + 1, y, z);
			const bS = isBlocked(x, y - 1, z), bN = isBlocked(x, y + 1, z);
			tW = !bW; tE = !bE; tS = !bS; tN = !bN;
			tSW = !isBlocked(x - 1, y - 1, z) && !bW && !bS;
			tSE = !isBlocked(x + 1, y - 1, z) && !bE && !bS;
			tNW = !isBlocked(x - 1, y + 1, z) && !bW && !bN;
			tNE = !isBlocked(x + 1, y + 1, z) && !bE && !bN;
		} else {
			tW = w(x, y, z); tE = e(x, y, z); tS = s(x, y, z); tN = n(x, y, z);
			tSW = tS && tW && w(x, y - 1, z) && s(x - 1, y, z);
			tSE = tS && tE && e(x, y - 1, z) && s(x + 1, y, z);
			tNW = tN && tW && w(x, y + 1, z) && n(x - 1, y, z);
			tNE = tN && tE && e(x, y + 1, z) && n(x + 1, y, z);
		}
		if (tW) out.push(pack(x - 1, y, z));
		if (tE) out.push(pack(x + 1, y, z));
		if (tS) out.push(pack(x, y - 1, z));
		if (tN) out.push(pack(x, y + 1, z));
		if (tSW) out.push(pack(x - 1, y - 1, z));
		if (tSE) out.push(pack(x + 1, y - 1, z));
		if (tNW) out.push(pack(x - 1, y + 1, z));
		if (tNE) out.push(pack(x + 1, y + 1, z));
		return out;
	}

	return { edge, n, e, s, w, isBlocked, walkNeighbors };
}
