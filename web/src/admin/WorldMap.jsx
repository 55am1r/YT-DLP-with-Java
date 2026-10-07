// The dotted ASCII world map. Land is drawn in text — ':' inland, '.' along the coasts —
// and every place with devices becomes a clickable marker: o for one device, O for two or
// three, @ for four or more. Colour says how the place is known (shared precisely, from the
// IP, or the office LAN); a pulse means someone there is online right now.
//
// The grid is sized to the container: the column count is picked from the width so the
// characters stay legible, and the row count keeps degrees of latitude and longitude the
// same size on screen.
import { useEffect, useMemo, useRef, useState } from 'react'
import { cellOf, landGrid, LAT_BOTTOM, LAT_TOP } from './worldMask'
import { SOURCE, timeAgo } from './format'

const MAP_FONT = 'ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace'
const LINE = 1.1 // line height, in em
const RANK = { precise: 3, ip: 2, lan: 1 }
const DAY = 86_400_000
const TIP_ROWS = 6
/** How close (px) the pointer must come to a marker to pick it. */
const PICK_RADIUS = 20

const FILTERS = [
  { id: 'online', label: 'Online now', test: (d) => d.online },
  { id: 'day', label: '24 hours', test: (d, now) => now - d.lastSeen < DAY },
  { id: 'week', label: '7 days', test: (d, now) => now - d.lastSeen < 7 * DAY },
  { id: 'all', label: 'All time', test: () => true },
]

let advance = 0
/** Width of one monospace character as a share of the font size (≈0.6). */
function monoAdvance() {
  if (!advance) {
    const ctx = document.createElement('canvas').getContext('2d')
    ctx.font = `100px ${MAP_FONT}`
    advance = ctx.measureText('0').width / 100 || 0.6
  }
  return advance
}

function landText(line, from, to) {
  let s = ''
  for (let c = from; c < to; c++) {
    const v = line[c]
    s += v >= 0.5 ? ':' : v >= 0.15 ? '.' : ' '
  }
  return s
}

/** Group the devices into grid cells and decide how each marker looks. */
function markers(devices, cols, rows, selectedId) {
  const cells = new Map()
  for (const d of devices) {
    const { r, c } = cellOf(d.place.lat, d.place.lon, cols, rows)
    const key = `${r}:${c}`
    if (!cells.has(key)) cells.set(key, { key, r, c, devices: [] })
    cells.get(key).devices.push(d)
  }
  const byRow = new Map()
  for (const cell of cells.values()) {
    const n = cell.devices.length
    cell.glyph = n === 1 ? 'o' : n <= 3 ? 'O' : '@'
    cell.source = cell.devices.reduce((best, d) => (RANK[d.place.source] > RANK[best] ? d.place.source : best), 'lan')
    cell.online = cell.devices.some((d) => d.online)
    cell.selected = cell.devices.some((d) => d.id === selectedId)
    cell.devices.sort((a, b) => Number(b.online) - Number(a.online) || b.lastSeen - a.lastSeen)
    if (!byRow.has(cell.r)) byRow.set(cell.r, [])
    byRow.get(cell.r).push(cell)
  }
  byRow.forEach((list) => list.sort((a, b) => a.c - b.c))
  return { cells, byRow }
}

export default function WorldMap({ devices, selectedId, onSelect }) {
  const stage = useRef(null)
  const markerEls = useRef(new Map()) // cell key → its button, to find the one nearest the pointer
  const [width, setWidth] = useState(0)
  const [pointing, setPointing] = useState(false)
  const [filter, setFilter] = useState('week')
  const [tip, setTip] = useState(null) // { key, x, y, below, pinned }

  useEffect(() => {
    const el = stage.current
    if (!el) return undefined
    const ro = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  // A pinned list closes on Escape or a click anywhere else.
  useEffect(() => {
    if (!tip?.pinned) return undefined
    const close = (e) => {
      if (e.type === 'keydown' ? e.key === 'Escape' : !e.target.closest('.map-tip, .mk')) setTip(null)
    }
    document.addEventListener('pointerdown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('pointerdown', close)
      document.removeEventListener('keydown', close)
    }
  }, [tip?.pinned])

  const ratio = width ? monoAdvance() : 0.6
  const cols = width ? Math.max(64, Math.min(220, Math.floor(width / (ratio * 7.2)))) : 0
  const fontSize = cols ? width / (cols * ratio) : 0
  const rows = cols ? Math.max(14, Math.round((width * (LAT_TOP - LAT_BOTTOM)) / 360 / (fontSize * LINE))) : 0
  // Only rebuilt when the grid size changes, not on every 5 s refresh.
  const grid = useMemo(() => (cols ? landGrid(cols, rows) : []), [cols, rows])

  const now = Date.now()
  const test = FILTERS.find((f) => f.id === filter).test
  const inRange = devices.filter((d) => test(d, now))
  const placed = inRange.filter((d) => d.place)
  const { cells, byRow } = markers(placed, cols, rows, selectedId)
  const tipCell = tip ? cells.get(tip.key) : null

  // Markers are a few pixels wide and neighbouring cities sit side by side, so the pointer
  // picks whichever marker is nearest (within PICK_RADIUS) rather than whatever it lands on.
  // The buttons themselves stay for keyboard focus and screen readers.
  function nearest(x, y) {
    let best = null
    let bestDist = PICK_RADIUS * PICK_RADIUS
    markerEls.current.forEach((el, key) => {
      const b = el.getBoundingClientRect()
      const d = (b.left + b.width / 2 - x) ** 2 + (b.top + b.height / 2 - y) ** 2
      if (d < bestDist) {
        bestDist = d
        best = { cell: cells.get(key), el }
      }
    })
    return best?.cell ? best : null
  }

  function onPointerMove(e) {
    const hit = nearest(e.clientX, e.clientY)
    setPointing(!!hit)
    if (tip?.pinned) return
    if (hit && hit.cell.key !== tip?.key) showTip(hit.cell, hit.el)
    else if (!hit && tip) setTip(null)
  }

  function onStageClick(e) {
    if (e.target.closest('.map-tip')) return
    const hit = nearest(e.clientX, e.clientY)
    if (hit) open(hit.cell, hit.el)
  }

  function open(cell, el) {
    if (cell.devices.length === 1) onSelect(cell.devices[0].id)
    else showTip(cell, el, true)
  }

  function showTip(cell, el, pinned = false) {
    const box = stage.current.getBoundingClientRect()
    const m = el.getBoundingClientRect()
    const y = m.top - box.top
    setTip({ key: cell.key, x: m.left - box.left + m.width / 2, y: y < 150 ? m.bottom - box.top : y, below: y < 150, pinned })
  }

  return (
    <div className="worldmap">
      <div className="map-filters" role="group" aria-label="Show devices seen in">
        {FILTERS.map((f) => (
          <button
            key={f.id} type="button" className={`pill sm ${filter === f.id ? 'active' : ''}`}
            aria-pressed={filter === f.id} onClick={() => { setFilter(f.id); setTip(null) }}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div
        className={`map-stage ${pointing ? 'pointing' : ''}`} ref={stage} role="group"
        aria-label={`World map: ${placed.length} ${placed.length === 1 ? 'device' : 'devices'} shown`}
        onPointerMove={onPointerMove} onClick={onStageClick}
        onPointerLeave={() => { setPointing(false); setTip((t) => (t?.pinned ? t : null)) }}
      >
        {cols > 0 && (
          <pre className="map-grid" style={{ fontSize: `${fontSize}px`, lineHeight: LINE }}>
            {grid.map((line, r) => {
              const parts = []
              let from = 0
              for (const m of byRow.get(r) || []) {
                parts.push(<span key={`t${from}`} aria-hidden="true">{landText(line, from, m.c)}</span>)
                parts.push(
                  <button
                    key={m.key} type="button"
                    ref={(el) => { if (el) markerEls.current.set(m.key, el); else markerEls.current.delete(m.key) }}
                    className={`mk mk-${m.source}${m.online ? ' online' : ''}${m.selected ? ' selected' : ''}`}
                    aria-label={`${m.devices.length} ${m.devices.length === 1 ? 'device' : 'devices'} near ${m.devices[0].place.label}`}
                    onFocus={(e) => showTip(m, e.currentTarget)}
                    onBlur={() => setTip((t) => (t?.pinned ? t : null))}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(m, e.currentTarget) } }}
                  >
                    {m.glyph}
                  </button>,
                )
                from = m.c + 1
              }
              parts.push(<span key={`t${from}`} aria-hidden="true">{landText(line, from, cols)}</span>)
              return <div key={r} className="map-row">{parts}</div>
            })}
          </pre>
        )}

        {tipCell && (
          <div
            className={`map-tip glass ${tip.below ? 'below' : ''} ${tip.pinned ? 'pinned' : ''}`}
            style={{ left: Math.min(Math.max(tip.x, 130), width - 130), top: tip.y }}
            role={tip.pinned ? 'dialog' : 'tooltip'}
          >
            <ul>
              {tipCell.devices.slice(0, TIP_ROWS).map((d) => (
                <li key={d.id}>
                  {tip.pinned
                    ? <button type="button" className="link-btn" onClick={() => { setTip(null); onSelect(d.id) }}>{d.name}</button>
                    : <strong>{d.name}</strong>}
                  <span className="muted">{d.place.label} · {SOURCE[d.place.source]?.short}</span>
                  <span className="muted">
                    {d.online ? 'Online now' : `Seen ${timeAgo(d.lastSeen)}`} · {d.downloads} {d.downloads === 1 ? 'download' : 'downloads'}
                  </span>
                </li>
              ))}
            </ul>
            {tipCell.devices.length > TIP_ROWS && <span className="muted small">and {tipCell.devices.length - TIP_ROWS} more</span>}
            {!tip.pinned && <span className="muted small">{tipCell.devices.length > 1 ? 'Click to pick one' : 'Click for details'}</span>}
          </div>
        )}

        {cols > 0 && placed.length === 0 && (
          <div className="map-empty">
            {inRange.length
              ? 'These devices have no location yet — it appears within a minute of their first visit.'
              : filter === 'online' ? 'Nobody is online right now.' : 'No devices in this period.'}
          </div>
        )}
      </div>

      <div className="map-legend">
        <span><b className="mk-key mk-precise" aria-hidden="true">o</b> {SOURCE.precise.short} — shared by the device</span>
        <span><b className="mk-key mk-ip" aria-hidden="true">o</b> {SOURCE.ip.short} — approximate city</span>
        <span><b className="mk-key mk-lan" aria-hidden="true">o</b> {SOURCE.lan.short} — the Mac’s location</span>
        <span className="muted">o 1 · O 2–3 · @ 4+ devices · pulsing = online now</span>
        <span className="muted">
          {placed.length} on the map{inRange.length > placed.length ? ` · ${inRange.length - placed.length} without a location yet` : ''}
        </span>
      </div>
    </div>
  )
}

