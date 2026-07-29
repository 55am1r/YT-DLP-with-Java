import { useEffect, useRef, useState } from 'react'

const CONFIRM_MS = 5000

/**
 * Big primary "Download …" button that briefly says "Download Started!" after a click.
 * The two labels sit stacked and cross-slide vertically, so a user watching the
 * button gets clear visual confirmation their tap did something, without any modal
 * or toast getting in the way. The confirmation persists for 5s (long enough to
 * catch on any screen) then slides back to the original label.
 *
 * The button itself stays clickable throughout — a user can queue several downloads
 * in a row, and each click re-arms the 5-second window.
 */
export default function DownloadButton({ onClick, children, disabled }) {
  const [confirming, setConfirming] = useState(false)
  const timer = useRef(null)

  useEffect(() => () => clearTimeout(timer.current), [])

  function handle() {
    onClick?.()
    setConfirming(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setConfirming(false), CONFIRM_MS)
  }

  return (
    <button
      className={`btn btn-primary btn-lg dl-btn ${confirming ? 'dl-btn-confirming' : ''}`}
      onClick={handle}
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
