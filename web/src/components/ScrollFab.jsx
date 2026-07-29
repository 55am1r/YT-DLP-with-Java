import { useEffect, useState } from 'react'

/**
 * Small-screen jump button. On a phone the analysed panel is tall enough that the
 * Download button is far below the fold — this FAB gives a one-tap way to jump
 * straight to it, and back up again once you're there.
 *
 * The icon flips based on scroll position: chevron-down when there's more to see
 * below, chevron-up once you're near the bottom. Hidden entirely above 1000px, since
 * the desktop workspace fits without scrolling.
 */
export default function ScrollFab() {
  const [dir, setDir] = useState('down')  // 'down' → scroll to bottom, 'up' → back to top

  useEffect(() => {
    const measure = () => {
      const scrolled = window.scrollY
      const max = document.documentElement.scrollHeight - window.innerHeight
      // Past 60% of the scrollable range → assume the user is looking at the
      // Download area, so offer to go back up.
      setDir(max > 0 && scrolled > max * 0.6 ? 'up' : 'down')
    }
    measure()
    window.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    return () => {
      window.removeEventListener('scroll', measure)
      window.removeEventListener('resize', measure)
    }
  }, [])

  function jump() {
    if (dir === 'down') {
      // Prefer scrolling to the actual download button so the user lands where they
      // need to be, not just at the page bottom (footer/downloads sheet strip).
      const target = document.querySelector('.dl-btn')
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' })
      else window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' })
    } else {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    }
  }

  return (
    <button
      className="scroll-fab mobile-only"
      onClick={jump}
      aria-label={dir === 'down' ? 'Jump to download button' : 'Back to top'}
      title={dir === 'down' ? 'Jump to Download' : 'Back to top'}
    >
      <i className={`fa-solid ${dir === 'down' ? 'fa-chevron-down' : 'fa-chevron-up'}`} />
    </button>
  )
}
