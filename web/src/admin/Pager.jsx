import { PAGE_SIZES, pageList } from './paging'

/**
 * The footer under a paged list: "11–20 of 234 devices", rows per page, and the pages.
 * Stays out of the way while everything fits on the smallest page.
 */
export default function Pager({ pager, noun = 'rows' }) {
  const { page, pages, size, total, offset, setPage, setSize } = pager
  if (total <= PAGE_SIZES[0]) return null
  return (
    <nav className="pager" aria-label="Pages">
      <span className="muted small">
        {(offset + 1).toLocaleString()}–{Math.min(total, offset + size).toLocaleString()} of {total.toLocaleString()} {noun}
      </span>
      <label className="pager-size">
        <span className="muted small">Per page</span>
        <select className="select" value={size} onChange={(e) => setSize(Number(e.target.value))} aria-label="Rows per page">
          {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
        </select>
      </label>
      <span className="pager-pages">
        <button type="button" className="pager-btn" disabled={page <= 1} onClick={() => setPage(page - 1)}
          aria-label="Previous page" title="Previous page">
          <i className="fa-solid fa-chevron-left" aria-hidden="true" />
        </button>
        {pageList(page, pages).map((p, i) => (p === '…'
          ? <span key={`gap-${i}`} className="pager-gap" aria-hidden="true">…</span>
          : (
            <button key={p} type="button" className={`pager-btn ${p === page ? 'active' : ''}`}
              aria-current={p === page ? 'page' : undefined} aria-label={`Page ${p}`} onClick={() => p !== page && setPage(p)}>
              {p}
            </button>
          )))}
        <button type="button" className="pager-btn" disabled={page >= pages} onClick={() => setPage(page + 1)}
          aria-label="Next page" title="Next page">
          <i className="fa-solid fa-chevron-right" aria-hidden="true" />
        </button>
      </span>
    </nav>
  )
}
