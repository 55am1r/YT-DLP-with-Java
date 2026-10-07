// The admin panel's charts: one column chart and two kinds of bar. Single-series charts
// use one hue (--viz-1); the title names the series, so there's no legend box. Every chart
// sits next to a table or a list that carries the same numbers.
import { useEffect, useRef, useState } from 'react'

function useWidth() {
  const ref = useRef(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const el = ref.current
    if (!el) return undefined
    const ro = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, width]
}

/** 1, 2 or 5 × a power of ten — keeps the axis ticks on clean numbers. */
function niceStep(raw) {
  if (raw <= 1) return 1
  const p = 10 ** Math.floor(Math.log10(raw))
  const n = raw / p
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p
}

/** A column whose top corners are rounded and whose foot sits square on the baseline. */
function column(x, y, w, h, r) {
  const k = Math.min(r, w / 2, h)
  return `M${x},${y + h}V${y + k}Q${x},${y} ${x + k},${y}H${x + w - k}Q${x + w},${y} ${x + w},${y + k}V${y + h}Z`
}

/**
 * Columns over time or over a fixed set of buckets.
 * @param data [{ key, label, value, detail }]
 */
export function ColumnChart({ data, height = 172, format = (v) => v.toLocaleString(), ariaLabel }) {
  const [ref, width] = useWidth()
  const [hover, setHover] = useState(null)
  const max = Math.max(0, ...data.map((d) => d.value))
  const step = niceStep(max / 2)
  const top = Math.max(step, Math.ceil(max / step) * step)
  const ticks = []
  for (let t = 0; t <= top; t += step) ticks.push(t)

  const padL = 36
  const padR = 4
  const padT = 10
  const axisH = 22
  const plotW = Math.max(0, width - padL - padR)
  const plotH = height - padT - axisH
  const slot = data.length ? plotW / data.length : 0
  const barW = Math.max(2, Math.min(24, slot - 2)) // a 2px surface gap between neighbours
  const labelEvery = Math.max(1, Math.ceil(data.length / Math.max(1, Math.floor(plotW / 46))))
  const y = (v) => padT + plotH - (v / top) * plotH
  const tip = hover != null ? data[hover] : null

  return (
    <div className="chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={height} role="group" aria-label={ariaLabel}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={padL} x2={width - padR} y1={y(t)} y2={y(t)} className={t === 0 ? 'axis' : 'grid'} />
              <text x={padL - 8} y={y(t)} dy="0.32em" textAnchor="end" className="tick">{format(t)}</text>
            </g>
          ))}
          {data.map((d, i) => {
            const cx = padL + slot * i + slot / 2
            const h = (d.value / top) * plotH
            return (
              <g key={d.key} className={hover === i ? 'col hover' : 'col'}>
                {h > 0 && <path d={column(cx - barW / 2, y(d.value), barW, h, 4)} className="bar" />}
                {i % labelEvery === 0 && (
                  <text x={cx} y={height - 6} textAnchor="middle" className="tick">{d.label}</text>
                )}
                <rect
                  x={padL + slot * i} y={padT} width={slot} height={plotH} className="hit" tabIndex={0}
                  aria-label={`${d.label}: ${format(d.value)}${d.detail ? `, ${d.detail}` : ''}`}
                  onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}
                  onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                />
              </g>
            )
          })}
        </svg>
      )}
      {tip && (
        <div
          className="chart-tip"
          style={{ left: Math.min(Math.max(padL + slot * hover + slot / 2, 70), width - 70), top: y(tip.value) }}
        >
          <strong>{format(tip.value)}</strong>
          <span>{tip.label}</span>
          {tip.detail && <span className="muted">{tip.detail}</span>}
        </div>
      )}
    </div>
  )
}

/**
 * Ranked horizontal bars with the value at the end of each label row.
 * @param items [{ key, label, value, sub, icon, title }]
 */
export function BarList({ items, format = (v) => v.toLocaleString(), empty = 'Nothing yet' }) {
  if (!items.length) return <p className="muted small">{empty}</p>
  const max = Math.max(1, ...items.map((i) => i.value))
  return (
    <ul className="barlist">
      {items.map((it) => (
        <li key={it.key} title={it.title}>
          <div className="barlist-head">
            <span className="barlist-label">
              {it.icon && <i className={`fa-solid ${it.icon}`} aria-hidden="true" />}
              {it.label}
            </span>
            <span className="barlist-value">
              {format(it.value)}
              {it.sub && <span className="muted"> · {it.sub}</span>}
            </span>
          </div>
          <div className="barlist-track" aria-hidden="true">
            <div className="barlist-fill" style={{ width: `${(it.value / max) * 100}%` }} />
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * Part-to-whole for two or three parts: one bar split by 2px gaps, with a legend that
 * names every part and gives its count and share.
 * @param parts [{ key, label, value, color }]
 */
export function SplitBar({ parts }) {
  const total = parts.reduce((s, p) => s + p.value, 0)
  if (!total) return <p className="muted small">Nothing yet</p>
  return (
    <div className="split">
      <div className="split-bar" role="img" aria-label={parts.map((p) => `${p.label}: ${p.value}`).join(', ')}>
        {parts.filter((p) => p.value > 0).map((p) => (
          <span key={p.key} style={{ flexGrow: p.value, background: p.color }} title={`${p.label}: ${p.value}`} />
        ))}
      </div>
      <ul className="split-legend">
        {parts.map((p) => (
          <li key={p.key}>
            <span className="swatch" style={{ background: p.color }} aria-hidden="true" />
            <span>{p.label}</span>
            <strong>{p.value.toLocaleString()}</strong>
            <span className="muted">{Math.round((p.value / total) * 100)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
