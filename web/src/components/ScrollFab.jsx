import { useEffect, useState } from 'react'

/**
 * Small-screen jump button that only appears when scrolling is actually needed.
 * Two IntersectionObservers watch the page top (`.brand`) and the Download button
 * (`.dl-btn`):
 *
 *   both in view       → hide entirely (no scroll needed)
 *   only top visible   → chevron-down → tap scrolls to the download button
 *   only button visible→ chevron-up   → tap scrolls back to the top
 *
 * A MutationObserver re-attaches the observers when the panel re-renders (tab
 * switch, refresh, reanalyse), so we always track the current elements.
 */
export default function ScrollFab() {
  const [topVisible, setTopVisible] = useState(true)
  const [dlVisible, setDlVisible] = useState(false)

  useEffect(() => {
    let ioTop = null, ioDl = null
    let watchedTop = null, watchedDl = null

    const attach = () => {
      const top = document.querySelector('.brand')
      const dl = document.querySelector('.dl-btn')
      if (top !== watchedTop) {
        if (ioTop) ioTop.disconnect()
        watchedTop = top
        if (top) {
          ioTop = new IntersectionObserver(
            (entries) => setTopVisible(entries[0].isIntersecting),
            { root: null, threshold: 0.01 },
          )
          ioTop.observe(top)
        } else {
          setTopVisible(true) // no top element = don't offer up-scroll
        }
      }
      if (dl !== watchedDl) {
        if (ioDl) ioDl.disconnect()
        watchedDl = dl
        if (dl) {
          ioDl = new IntersectionObserver(
            (entries) => setDlVisible(entries[0].isIntersecting),
            { root: null, threshold: 0.01 },
          )
          ioDl.observe(dl)
        } else {
          setDlVisible(false)
        }
      }
    }

    attach()
    const mo = new MutationObserver(attach)
    mo.observe(document.body, { childList: true, subtree: true })

    return () => {
      if (ioTop) ioTop.disconnect()
      if (ioDl) ioDl.disconnect()
      mo.disconnect()
    }
  }, [])

  // Both in view = the whole workspace fits and there's nothing to jump to.
  if (topVisible && dlVisible) return null
  // Also hide before a link is analysed — there's no download button anywhere.
  if (!document.querySelector('.dl-btn')) return null

  const dir = dlVisible ? 'up' : 'down'

  function jump() {
    if (dir === 'up') {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      const el = document.querySelector('.dl-btn')
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }

  return (
    <button
      className="scroll-fab mobile-only"
      onClick={jump}
      aria-label={dir === 'up' ? 'Back to top' : 'Jump to download button'}
      title={dir === 'up' ? 'Back to top' : 'Jump to Download'}
    >
      <i className={`fa-solid ${dir === 'up' ? 'fa-chevron-up' : 'fa-chevron-down'}`} />
    </button>
  )
}
