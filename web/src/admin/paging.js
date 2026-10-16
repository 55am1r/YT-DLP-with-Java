// Page-at-a-time lists for the admin panel: 10, 20, 30, 50 or 100 rows a page, the choice
// remembered per list. usePaged slices rows already in hand; usePager alone drives a list
// the server pages itself, from the total it reports.
import { useCallback, useEffect, useRef, useState } from 'react'

export const PAGE_SIZES = [10, 20, 30, 50, 100]
const SIZE_KEY = 'ez-admin-rows:'

function savedSize(id, fallback) {
  try {
    const n = Number(localStorage.getItem(SIZE_KEY + id))
    return PAGE_SIZES.includes(n) ? n : fallback
  } catch {
    return fallback
  }
}

/** 1 … 4 5 [6] 7 8 … 24 style: both ends, a neighbour either side, "…" only where it hides two or more. */
export function pageList(page, pages) {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1)
  const lo = Math.max(2, page - 1)
  const hi = Math.min(pages - 1, page + 1)
  const out = [1]
  if (lo === 3) out.push(2)
  else if (lo > 3) out.push('…')
  for (let p = lo; p <= hi; p++) out.push(p)
  if (hi === pages - 2) out.push(pages - 1)
  else if (hi < pages - 2) out.push('…')
  out.push(pages)
  return out
}

/** Room left above a list brought back into view, so its heading shows too. */
const HEADROOM = 56

/** What the element scrolls inside — the drawer's body — or null for the window. */
function scrollBox(el) {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const oy = getComputedStyle(p).overflowY
    if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight) return p
  }
  return null
}

/**
 * Which page of `total` rows is showing. A new `resetKey` (another search or filter) goes back
 * to page 1; a total that shrinks under the current page falls back to the last real page.
 * Attach `topRef` to the list: turning the page from the pager below it brings its top back
 * into view.
 */
export function usePager(total, { id, size: initial = 10, resetKey } = {}) {
  const [size, setSizeState] = useState(() => savedSize(id, initial))
  const [page, setPageState] = useState(1)
  const topRef = useRef(null)
  const turned = useRef(false) // the person turned the page, so bring the list's top into view
  const pages = Math.max(1, Math.ceil(total / size))
  const current = Math.min(page, pages)

  useEffect(() => { setPageState(1) }, [resetKey])
  useEffect(() => { if (page > pages) setPageState(pages) }, [page, pages])

  useEffect(() => {
    const el = topRef.current
    if (!turned.current || !el) return
    turned.current = false
    const box = scrollBox(el)
    const above = el.getBoundingClientRect().top - (box ? box.getBoundingClientRect().top : 0)
    if (above >= 0) return
    // A short way glides; further than a screen jumps — a long smooth scroll is slow, and the
    // panel's 5 s refresh can cut it off halfway.
    const far = -above > (box ? box.clientHeight : window.innerHeight)
    const still = far || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    ;(box || window).scrollBy({ top: above - HEADROOM, behavior: still ? 'auto' : 'smooth' })
  }, [current, size])

  const setPage = useCallback((p) => { turned.current = true; setPageState(p) }, [])
  const setSize = useCallback((n) => {
    turned.current = true
    setSizeState(n)
    setPageState(1)
    try { localStorage.setItem(SIZE_KEY + id, String(n)) } catch { /* private window: just not remembered */ }
  }, [id])

  return { page: current, pages, size, total, offset: (current - 1) * size, setPage, setSize, topRef }
}

/** The rows on the current page, and the pager that moves through `items`. */
export function usePaged(items, opts) {
  const pager = usePager(items.length, opts)
  return [items.slice(pager.offset, pager.offset + pager.size), pager]
}
