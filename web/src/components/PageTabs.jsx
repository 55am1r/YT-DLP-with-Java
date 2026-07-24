/**
 * One tab per analysed link. Each link keeps its own media panel and its own
 * downloads, so results never mix between links.
 */
export default function PageTabs({ pages, activeId, onSelect, onClose }) {
  if (pages.length < 2) return null
  return (
    <div className="tabs">
      {pages.map((p) => (
        <div
          key={p.id}
          className={`tab glass ${p.id === activeId ? 'active' : ''}`}
          onClick={() => onSelect(p.id)}
          title={p.analysis?.title || p.url}
        >
          <span className="tab-label">{p.analysis?.title || 'Loading…'}</span>
          {p.jobs.length > 0 && <span className="tab-count">{p.jobs.length}</span>}
          <button
            className="tab-x"
            onClick={(e) => {
              e.stopPropagation()
              onClose(p.id)
            }}
            aria-label="Close tab"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
