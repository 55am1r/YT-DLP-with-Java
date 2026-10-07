import { useEffect, useState } from 'react'
import { sendLocation } from '../api'
import {
  locationPermission, rememberChoice, requestPosition, shouldAsk, shouldRefreshQuietly, storedChoice,
} from '../telemetry'

const PERMISSION_DENIED = 1

/**
 * The optional "share your precise location with the admin?" card. The browser's own
 * prompt only appears after "Share location" is pressed here, and "No thanks" is
 * respected for 30 days. Someone who already agreed gets their position refreshed
 * quietly when the app opens.
 */
export default function LocationPrompt() {
  const [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState(null)

  useEffect(() => {
    let alive = true
    locationPermission().then((permission) => {
      if (!alive) return
      const stored = storedChoice()
      if (shouldRefreshQuietly(permission, stored)) share(true)
      else if (shouldAsk(permission, stored)) setVisible(true)
    })
    return () => { alive = false }
    // Once per app load, on purpose.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function share(quiet = false) {
    setBusy(true)
    setNote(null)
    try {
      const position = await requestPosition()
      await sendLocation({ ...position, outcome: 'granted' })
      rememberChoice('granted')
      setVisible(false)
    } catch (err) {
      if (err?.code === PERMISSION_DENIED) {
        sendLocation({ outcome: 'denied' })
        rememberChoice('denied')
        setVisible(false)
      } else if (!quiet) {
        setNote('Couldn’t get a location fix just now — try again in a moment.')
      }
    } finally {
      setBusy(false)
    }
  }

  function decline() {
    sendLocation({ outcome: 'declined' })
    rememberChoice('declined')
    setVisible(false)
  }

  if (!visible) return null
  return (
    <div className="notice glass location-card" role="region" aria-label="Share your location">
      <div className="notice-icon" aria-hidden="true"><i className="fa-solid fa-location-dot" /></div>
      <div className="notice-body">
        <strong>Share your location with the EZ-Tube admin?</strong>
        <span className="muted">
          It’s optional. Without it the admin only sees an approximate city, worked out from your internet connection.
        </span>
        {note && <span className="muted">{note}</span>}
      </div>
      <div className="notice-actions">
        <button type="button" className="btn btn-sm" onClick={decline} disabled={busy}>No thanks</button>
        <button type="button" className="btn btn-sm btn-primary" onClick={() => share()} disabled={busy}>
          <i className="fa-solid fa-location-crosshairs" /> {busy ? 'Locating…' : 'Share location'}
        </button>
      </div>
    </div>
  )
}
