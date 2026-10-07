import { useEffect, useState } from 'react'
import { sendLocation } from '../api'
import { CHOICE_EVENT, rememberChoice, storedChoice } from '../telemetry'

const sharing = () => storedChoice()?.choice === 'granted'

/** A quiet footer note while this device shares its location — with the way to stop. */
export default function LocationStatus() {
  const [on, setOn] = useState(sharing)

  useEffect(() => {
    const follow = () => setOn(sharing())
    window.addEventListener(CHOICE_EVENT, follow)
    return () => window.removeEventListener(CHOICE_EVENT, follow)
  }, [])

  function stop() {
    sendLocation({ outcome: 'declined' }) // the server deletes the stored position
    rememberChoice('declined')
  }

  if (!on) return null
  return (
    <span className="location-status">
      <i className="fa-solid fa-location-dot" aria-hidden="true" /> Sharing your location with the admin ·{' '}
      <button type="button" className="link-btn" onClick={stop}>Stop sharing</button>
    </span>
  )
}
