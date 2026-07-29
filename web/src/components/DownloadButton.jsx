import { useEffect, useRef, useState } from 'react'

const CONFIRM_MS = 5000

/**
 * Big primary "Download …" button that briefly says "Download Started!" after a job
 * has ACTUALLY started on the server. The animation is triggered by a counter prop
 * (`signal`) rather than by the click, so a click that opens the duplicate-download
 * confirmation modal doesn't misleadingly confirm — the button only celebrates once
 * the user either agrees to download it again, or the request was never a duplicate
 * to begin with.
 *
 * Two labels sit stacked and cross-slide vertically. The face tints green for the
 * 5-second window, then eases back.
 */
export default function DownloadButton({ onClick, children, disabled, signal = 0 }) {
  const [confirming, setConfirming] = useState(false)
  const timer = useRef(null)
  // Skip the counter's initial value; we only want to react to CHANGES.
  const firstSeen = useRef(signal)

  useEffect(() => {
    if (signal === firstSeen.current) return
    setConfirming(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setConfirming(false), CONFIRM_MS)
  }, [signal])

  useEffect(() => () => clearTimeout(timer.current), [])

  return (
    <button
      className={`btn btn-primary btn-lg dl-btn ${confirming ? 'dl-btn-confirming' : ''}`}
      onClick={onClick}
      disabled={disabled}
    >
      {/* Two stacked labels. Whichever is "on top" (per the confirming state) sits at
          its natural position; the other is translated off-screen and hidden from
          screen readers. The transition on transform+opacity is what animates it. */}
      <span className="dl-btn-label dl-btn-idle" aria-hidden={confirming}>
        <i className="fa-solid fa-download" /> {children}
      </span>
      <span className="dl-btn-label dl-btn-done" aria-hidden={!confirming}>
        <i className="fa-solid fa-circle-check" /> Download Started!
      </span>
    </button>
  )
}
