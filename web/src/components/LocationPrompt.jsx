import { useEffect } from 'react'
import { sendLocation } from '../api'
import { locationPermission, requestPosition, shouldRefreshQuietly, storedChoice } from '../telemetry'

/**
 * No in-site "share your location?" card any more — we don't ask for location from
 * inside the site. The only thing left is honouring a permission the *browser* already
 * granted: someone who allowed location at the browser level gets their position
 * refreshed quietly on open. Everyone else is left alone and the admin falls back to the
 * approximate IP city. Renders nothing.
 */
export default function LocationPrompt() {
  useEffect(() => {
    let alive = true
    locationPermission().then(async (permission) => {
      if (!alive || !shouldRefreshQuietly(permission, storedChoice())) return
      try {
        const position = await requestPosition()
        await sendLocation({ ...position, outcome: 'granted' })
      } catch {
        // browser revoked or a GPS hiccup — nothing to show, nothing to do
      }
    })
    return () => { alive = false }
    // Once per app load, on purpose.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return null
}
