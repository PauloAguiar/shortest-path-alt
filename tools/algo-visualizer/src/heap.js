// A tiny binary min-heap of [key, ...payload] arrays, ordered by key.
// The toy version sorted an array per pop; on the real map (hundreds of
// thousands of nodes) that would be quadratic — this is the same heap the
// engine's IntMinHeap plays.
export class MinHeap {
	constructor() { this.a = []; }
	get size() { return this.a.length; }
	push(item) {
		const a = this.a;
		a.push(item);
		let i = a.length - 1;
		while (i > 0) {
			const p = (i - 1) >> 1;
			if (a[p][0] <= a[i][0]) break;
			[a[p], a[i]] = [a[i], a[p]];
			i = p;
		}
	}
	pop() {
		const a = this.a;
		if (!a.length) return undefined;
		const top = a[0];
		const last = a.pop();
		if (a.length) {
			a[0] = last;
			let i = 0;
			for (;;) {
				const l = 2 * i + 1, r = l + 1;
				let m = i;
				if (l < a.length && a[l][0] < a[m][0]) m = l;
				if (r < a.length && a[r][0] < a[m][0]) m = r;
				if (m === i) break;
				[a[m], a[i]] = [a[i], a[m]];
				i = m;
			}
		}
		return top;
	}
	top(n) { return [...this.a].sort((p, q) => p[0] - q[0]).slice(0, n); }
}
