import { useEffect, useRef } from 'react'

/**
 * One tab per analysed link — shown even for a single link, so the layout is
 * consistent. The strip scrolls horizontally, and the active tab is always scrolled
 * fully into view so you can see which link is working and what sits either side of it.
 */
export default function PageTabs({ pages, activeId, onSelect, onClose }) {
  const stripRef = useRef(null)
  const activeRef = useRef(null)

  useEffect(() => {
    activeRef.current?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
  }, [activeId, pages.length])

  if (pages.length === 0) return null

  return (
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
  )
}
