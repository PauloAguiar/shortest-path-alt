import { useState, useEffect, useRef } from 'react'
import { loadCollision, pack, unX, unY } from './collision.js'
import { parseTransports } from './transports.js'
import { buildTrace, makeState, applyEvent } from './engine.js'
import './styles.css'

const PRESETS = [
	{ name: 'Lumbridge → Varrock', s: [3222, 3218], g: [3213, 3424] },
	{ name: 'GE → Shilo Village', s: [3164, 3487], g: [2852, 2954] },
	{ name: 'Deep wilderness → Varrock', s: [3004, 3937], g: [3213, 3424] },
	{ name: 'Ardougne dock → Brimhaven', s: [2683, 3271], g: [2772, 3234] },
]

export default function App() {
	const [assets, setAssets] = useState(null)
	const [status, setStatus] = useState('loading the real map + transports…')
	const [preset, setPreset] = useState(0)
	const [startXY, setStartXY] = useState(PRESETS[0].s)
	const [goalXY, setGoalXY] = useState(PRESETS[0].g)
	const [useH, setUseH] = useState(true)
	const [trace, setTrace] = useState(null)
	const [step, setStep] = useState(0)
	const [playing, setPlaying] = useState(false)
	const [speed, setSpeed] = useState(20)
	const [clickMode, setClickMode] = useState(null) // 'start' | 'goal' | null
	const canvasRef = useRef(null)
	const viewRef = useRef({ cx: 3200, cy: 3300, scale: 3 }) // px per tile
	const cacheRef = useRef({ at: -1, st: null })
	const baseRef = useRef(null) // offscreen collision base layer for the current view

	// ── asset loading ────────────────────────────────────────────────────
	useEffect(() => {
		(async () => {
			try {
				const zipBuf = await (await fetch('collision-map.zip')).arrayBuffer()
				const col = await loadCollision(zipBuf)
				const manifest = await (await fetch('transports/manifest.json')).json()
				const files = await Promise.all(manifest.map(async name =>
					({ name, text: await (await fetch('transports/' + name)).text() })))
				const data = parseTransports(files)
				setAssets({ col, data })
				setStatus(`real map loaded — ${data.transports.length} transports, ${data.teleports.length} teleports. Pick a preset and Run.`)
			} catch (e) {
				setStatus('failed to load assets: ' + e + ' — run "npm run assets" first')
			}
		})()
	}, [])

	// ── run the pipeline ─────────────────────────────────────────────────
	const run = () => {
		if (!assets) return
		setStatus('computing the full trace…')
		setPlaying(false)
		setTimeout(() => {
			const start = pack(startXY[0], startXY[1], 0)
			const goal = pack(goalXY[0], goalXY[1], 0)
			const t0 = performance.now()
			const t = buildTrace({ col: assets.col, data: assets.data, start, goal, useHeuristic: useH })
			cacheRef.current = { at: -1, st: null }
			setTrace(t)
			setStep(0)
			setPlaying(true)
			// centre the viewport on the action
			const v = viewRef.current
			v.cx = (startXY[0] + goalXY[0]) / 2
			v.cy = (startXY[1] + goalXY[1]) / 2
			v.scale = Math.max(1.2, Math.min(6, 560 / (Math.max(
				Math.abs(startXY[0] - goalXY[0]), Math.abs(startXY[1] - goalXY[1])) + 80)))
			baseRef.current = null
			setStatus(`trace built in ${Math.round(performance.now() - t0)} ms — ${t.ev.length} events, ${t.routes.length} routes`)
		}, 30)
	}

	const stateAt = (t) => {
		let { at, st } = cacheRef.current
		if (!st || t < at) { st = makeState(); at = -1 }
		for (let i = at + 1; i <= t && i < trace.ev.length; i++) applyEvent(st, trace.ev[i])
		cacheRef.current = { at: Math.min(t, trace.ev.length - 1), st }
		return st
	}

	useEffect(() => {
		if (!playing || !trace) return
		const h = setInterval(() => setStep(s => {
			const n = Math.min(s + speed, trace.ev.length - 1)
			if (n === trace.ev.length - 1) setPlaying(false)
			return n
		}), 30)
		return () => clearInterval(h)
	}, [playing, speed, trace])

	// ── rendering ────────────────────────────────────────────────────────
	const CW = 900, CH = 620
	const tileAt = (px, py) => {
		const v = viewRef.current
		return [Math.floor(v.cx + (px - CW / 2) / v.scale), Math.floor(v.cy - (py - CH / 2) / v.scale)]
	}
	const draw = () => {
		const canvas = canvasRef.current
		if (!canvas || !assets) return
		const ctx = canvas.getContext('2d')
		const v = viewRef.current
		const x0 = Math.floor(v.cx - CW / 2 / v.scale) - 1, x1 = Math.ceil(v.cx + CW / 2 / v.scale) + 1
		const y0 = Math.floor(v.cy - CH / 2 / v.scale) - 1, y1 = Math.ceil(v.cy + CH / 2 / v.scale) + 1
		const sx = wx => (wx - v.cx) * v.scale + CW / 2
		const sy = wy => (v.cy - wy) * v.scale + CH / 2

		// base collision layer, cached per viewport
		const key = [v.cx, v.cy, v.scale].join(',')
		if (!baseRef.current || baseRef.current.key !== key) {
			const off = document.createElement('canvas')
			off.width = CW; off.height = CH
			const octx = off.getContext('2d')
			octx.fillStyle = '#101114'
			octx.fillRect(0, 0, CW, CH)
			for (let x = x0; x <= x1; x++) {
				for (let y = y0; y <= y1; y++) {
					octx.fillStyle = assets.col.isBlocked(x, y, 0) ? '#2c2f37' : '#191c22'
					octx.fillRect(sx(x), sy(y + 1), v.scale + 0.5, v.scale + 0.5)
				}
			}
			baseRef.current = { key, off }
		}
		ctx.drawImage(baseRef.current.off, 0, 0)

		if (trace) {
			const st = stateAt(step)
			// field heat
			for (let x = x0; x <= x1; x++) {
				for (let y = y0; y <= y1; y++) {
					const d = st.field.get(pack(x, y, 0))
					if (d === undefined) continue
					const t = Math.min(1, d / 600)
					ctx.fillStyle = `rgba(${40 + 60 * t}, ${110 - 60 * t}, ${220 - 140 * t}, 0.4)`
					ctx.fillRect(sx(x), sy(y + 1), v.scale + 0.5, v.scale + 0.5)
				}
			}
			// settled (current search)
			ctx.fillStyle = 'rgba(60, 200, 106, 0.5)'
			for (const p of st.settled) {
				const x = unX(p), y = unY(p)
				if (x < x0 || x > x1 || y < y0 || y > y1) continue
				ctx.fillRect(sx(x), sy(y + 1), v.scale + 0.5, v.scale + 0.5)
			}
			// routes
			st.routes.forEach((r, ri) => {
				ctx.strokeStyle = ri === st.routes.length - 1 ? '#ff9a1f' : 'rgba(255,154,31,.35)'
				ctx.lineWidth = 2.5
				ctx.beginPath()
				let prev = null
				for (const p of r.path) {
					const px = sx(unX(p)) + v.scale / 2, py = sy(unY(p) + 1) + v.scale / 2
					if (prev) ctx.lineTo(px, py); else ctx.moveTo(px, py)
					prev = p
				}
				ctx.stroke()
			})
		}
		// start / goal pins
		const pin = (xy, color, label) => {
			ctx.fillStyle = color
			ctx.beginPath()
			ctx.arc(sx(xy[0]) + v.scale / 2, sy(xy[1] + 1) + v.scale / 2, 6, 0, Math.PI * 2)
			ctx.fill()
			ctx.fillStyle = '#101114'
			ctx.font = 'bold 9px Consolas'
			ctx.textAlign = 'center'
			ctx.fillText(label, sx(xy[0]) + v.scale / 2, sy(xy[1] + 1) + v.scale / 2 + 3)
		}
		pin(startXY, '#e8e8e8', 'S')
		pin(goalXY, '#3cc86a', 'G')
	}
	useEffect(draw)

	// pan / zoom / click-to-place
	useEffect(() => {
		const canvas = canvasRef.current
		if (!canvas) return
		let dragging = false, moved = false, lx = 0, ly = 0
		const down = e => { dragging = true; moved = false; lx = e.clientX; ly = e.clientY }
		const move = e => {
			if (!dragging) return
			const v = viewRef.current
			const dx = e.clientX - lx, dy = e.clientY - ly
			if (Math.abs(dx) + Math.abs(dy) > 3) moved = true
			v.cx -= dx / v.scale; v.cy += dy / v.scale
			lx = e.clientX; ly = e.clientY
			baseRef.current = null
			draw()
		}
		const up = e => {
			if (dragging && !moved && clickMode) {
				const rect = canvas.getBoundingClientRect()
				const [wx, wy] = tileAt(e.clientX - rect.left, e.clientY - rect.top)
				if (clickMode === 'start') setStartXY([wx, wy]); else setGoalXY([wx, wy])
				setClickMode(null)
			}
			dragging = false
		}
		const wheel = e => {
			e.preventDefault()
			const v = viewRef.current
			v.scale = Math.max(0.8, Math.min(14, v.scale * (e.deltaY < 0 ? 1.25 : 0.8)))
			baseRef.current = null
			draw()
		}
		canvas.addEventListener('mousedown', down)
		window.addEventListener('mousemove', move)
		window.addEventListener('mouseup', up)
		canvas.addEventListener('wheel', wheel, { passive: false })
		return () => {
			canvas.removeEventListener('mousedown', down)
			window.removeEventListener('mousemove', move)
			window.removeEventListener('mouseup', up)
			canvas.removeEventListener('wheel', wheel)
		}
	}, [assets, clickMode, trace])

	const st = trace ? stateAt(step) : null
	const jump = name => {
		const i = trace.ev.findIndex(e => e.t === 'phase' && e.phase.startsWith(name))
		if (i >= 0) { setStep(i); setPlaying(true) }
	}

	return <>
		<div className="top">
			<div>
				<canvas ref={canvasRef} width={CW} height={CH}/>
				<div className="panel legend" style={{ marginTop: 8 }}>
					<span className="muted">{status}</span><br/>
					<span><span className="sw" style={{ background: 'rgba(45,105,210,.6)' }}/>field (cost→goal)</span>
					<span><span className="sw" style={{ background: 'rgba(60,200,106,.6)' }}/>settled (current search)</span>
					<span><span className="sw" style={{ background: '#ff9a1f' }}/>routes</span>
					<span><span className="sw" style={{ background: '#2c2f37' }}/>blocked</span>
					<span className="muted"> drag to pan, wheel to zoom</span>
				</div>
			</div>
			<div className="side">
				<div className="panel">
					<h3>Scenario</h3>
					<select value={preset} onChange={e => {
						const i = +e.target.value
						setPreset(i); setStartXY(PRESETS[i].s); setGoalXY(PRESETS[i].g)
					}}>
						{PRESETS.map((p, i) => <option key={i} value={i}>{p.name}</option>)}
					</select>
					<div style={{ marginTop: 6 }}>
						<button onClick={() => setClickMode('start')}>{clickMode === 'start' ? 'click the map…' : `start (${startXY})`}</button>{' '}
						<button onClick={() => setClickMode('goal')}>{clickMode === 'goal' ? 'click the map…' : `goal (${goalXY})`}</button>
					</div>
					<div style={{ marginTop: 6 }}>
						<button className="primary" onClick={run} disabled={!assets}>Run</button>{' '}
						<label><input type="checkbox" checked={useH} onChange={e => setUseH(e.target.checked)}/> heuristic</label>
					</div>
				</div>
				<div className="panel">
					<div className="phase">{st ? st.phase : '—'}</div>
					<div className="muted">{st ? st.note : 'run a scenario'}</div>
					{trace && <div className="muted">event {step} / {trace.ev.length - 1}</div>}
				</div>
				<div className="panel">
					<h3>Priority queue (top of heap)</h3>
					<div className="pq">{st && st.pqTop.length
						? '   f      g      h   tile\n' + st.pqTop.map(e =>
							`${String(e.f).padStart(5)} ${String(e.g).padStart(6)} ${String(e.f - e.g).padStart(6)}  (${unX(e.cell)},${unY(e.cell)})`).join('\n')
						: '(empty)'}</div>
				</div>
				<div className="panel routes">
					<h3>Routes found</h3>
					{st && st.routes.map((r, i) => <div key={i}>#{i + 1} — {r.method}, cost {r.cost}</div>)}
					{(!st || !st.routes.length) && <div className="muted">none yet</div>}
				</div>
				<div className="panel">
					<h3>Event log</h3>
					<div className="log">{st && st.log.slice(-40).map((m, i) => <div key={i}>{m}</div>)}</div>
				</div>
			</div>
		</div>
		<div className="controls">
			<button className="primary" onClick={() => setPlaying(p => !p)} disabled={!trace}>{playing ? 'Pause' : 'Play'}</button>
			<button onClick={() => trace && setStep(s => Math.min(s + 1, trace.ev.length - 1))} disabled={!trace}>Step</button>
			<label>speed <input type="range" min="1" max="200" value={speed} onChange={e => setSpeed(+e.target.value)}/></label>
			{trace && <>
				<span className="muted">jump:</span>
				<button onClick={() => jump('FIELD')}>field</button>
				<button onClick={() => jump('SEARCH #0')}>#0</button>
				<button onClick={() => jump('SEARCH #1')}>#1</button>
				<button onClick={() => jump('SEARCH #2')}>#2</button>
				<input type="range" min="0" max={trace.ev.length - 1} value={step} style={{ flex: 1, minWidth: 160 }}
					onChange={e => { setStep(+e.target.value); setPlaying(false) }}/>
			</>}
		</div>
	</>
}
