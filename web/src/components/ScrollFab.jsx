import { useEffect, useState } from 'react'

/**
 * Small-screen jump button that swaps direction based on whether the Download button
 * is actually on screen. IntersectionObserver watches `.dl-btn`:
 *
 *   button off-screen → chevron-down → tap scrolls to it
 *   button in-view    → chevron-up   → tap scrolls back to the top
 *
 * A MutationObserver picks up when the download button is added / replaced after a
 * link is (re)analysed, so the observer always tracks the current one.
 */
export default function ScrollFab() {
  const [dlVisible, setDlVisible] = useState(false)

  useEffect(() => {
    let io = null
    let watched = null

    const attach = () => {
      const el = document.querySelector('.dl-btn')
      if (el === watched) return
      if (io) io.disconnect()
      watched = el
      if (!el) { setDlVisible(false); return }
      io = new IntersectionObserver(
        (entries) => setDlVisible(entries[0].isIntersecting),
        // Any part of the button showing counts as visible.
        { root: null, threshold: 0.01 },
      )
      io.observe(el)
    }

    attach()
    // Panels re-render on tab switch, refresh, etc., so re-attach when the DOM changes.
    const mo = new MutationObserver(attach)
    mo.observe(document.body, { childList: true, subtree: true })

    return () => { if (io) io.disconnect(); mo.disconnect() }
  }, [])

  function jump() {
    if (dlVisible) {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      const el = document.querySelector('.dl-btn')
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      else window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' })
    }
  }

  return (
    <button
      className="scroll-fab mobile-only"
      onClick={jump}
      aria-label={dlVisible ? 'Back to top' : 'Jump to download button'}
      title={dlVisible ? 'Back to top' : 'Jump to Download'}
    >
      <i className={`fa-solid ${dlVisible ? 'fa-chevron-up' : 'fa-chevron-down'}`} />
    </button>
  )
}
