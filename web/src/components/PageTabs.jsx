import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

/**
 * One tab per analysed link. Overflow scrolls horizontally, but the scroll is driven
 * by circular arrow buttons rather than a scrollbar — the arrows only appear when the
 * strip actually overflows, and each is disabled at its end of the run. The active
 * tab always scrolls into view when it changes.
 */
export default function PageTabs({ pages, activeId, onSelect, onClose }) {
  const stripRef = useRef(null)
  const activeRef = useRef(null)
  const [nav, setNav] = useState({ overflow: false, atStart: true, atEnd: true })

  // Recompute whether arrows are needed and which ends we've hit. Runs on tab changes,
  // window resize, and every scroll of the strip.
  const measure = useCallback(() => {
    const el = stripRef.current
    if (!el) return
    const overflow = el.scrollWidth > el.clientWidth + 1
    const atStart = el.scrollLeft <= 1
    const atEnd = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1
    setNav({ overflow, atStart, atEnd })
  }, [])

  useLayoutEffect(() => { measure() }, [pages, measure])
  useEffect(() => {
    const el = stripRef.current
    if (!el) return
    el.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    return () => {
      el.removeEventListener('scroll', measure)
      window.removeEventListener('resize', measure)
    }
  }, [measure])

  // Keep the active tab visible when the user picks a different one.
  useEffect(() => {
    activeRef.current?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
  }, [activeId, pages.length])

  function nudge(dir) {
    const el = stripRef.current
    if (!el) return
    // Scroll roughly one visible width, so a single click moves the user meaningfully
    // through the strip regardless of how wide each tab is.
    el.scrollBy({ left: dir * Math.max(160, el.clientWidth * 0.85), behavior: 'smooth' })
  }

  if (pages.length === 0) return null

  return (
    <div className="tabs-wrap">
      {nav.overflow && (
        <button
          className="icon-round tabs-nav"
          onClick={() => nudge(-1)}
          disabled={nav.atStart}
          aria-label="Scroll tabs left"
          title="Previous tabs"
        >
          <i className="fa-solid fa-chevron-left" />
        </button>
      )}

      <div className="tabs" ref={stripRef} role="tablist">
        {pages.map((p) => {
          const on = p.id === activeId
          return (
            <div
              key={p.id}
              ref={on ? activeRef : null}
              className={`tab glass ${on ? 'active' : ''}`}
              onClick={() => onSelect(p.id)}
              title={p.analysis?.title || p.url}
              role="tab"
              aria-selected={on}
            >
              <span className="tab-label">{p.analysis?.title || 'Loading…'}</span>
              {p.jobs.length > 0 && <span className="tab-count">{p.jobs.length}</span>}
              <button
                className="tab-x"
                onClick={(e) => { e.stopPropagation(); onClose(p.id) }}
                aria-label="Close tab"
              >
                ×
              </button>
            </div>
          )
        })}
      </div>

      {nav.overflow && (
        <button
          className="icon-round tabs-nav"
          onClick={() => nudge(1)}
          disabled={nav.atEnd}
          aria-label="Scroll tabs right"
          title="More tabs"
        >
          <i className="fa-solid fa-chevron-right" />
        </button>
      )}
    </div>
  )
}
