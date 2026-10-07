// What the downloader tells the server about this browser, and the rules for asking a
// teammate whether they want to share their precise location with the admin.
//
// Without consent the admin only ever sees an approximate city from the IP address. The
// browser's own location prompt is never triggered cold: our card asks first, and only
// "Share location" on it calls the geolocation API.

const CHOICE_KEY = 'ez-location-choice'
const ASK_AGAIN_AFTER = 30 * 86_400_000

/** Sent once per app load. */
export function helloInfo() {
  return {
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    language: navigator.language,
    screen: `${window.screen.width}x${window.screen.height}`,
    touch: navigator.maxTouchPoints > 0,
    secure: window.isSecureContext,
  }
}

/**
 * 'granted' | 'denied' | 'prompt' — or 'unsupported' where location can't be asked for
 * (plain-http LAN link, no GPS API), or 'unknown' on browsers without the Permissions API.
 */
export async function locationPermission() {
  if (!window.isSecureContext || !('geolocation' in navigator)) return 'unsupported'
  if (!navigator.permissions?.query) return 'unknown'
  try {
    return (await navigator.permissions.query({ name: 'geolocation' })).state
  } catch {
    return 'unknown'
  }
}

export function storedChoice() {
  try {
    const raw = localStorage.getItem(CHOICE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/** @param choice 'granted' | 'declined' | 'denied' */
export function rememberChoice(choice) {
  try {
    localStorage.setItem(CHOICE_KEY, JSON.stringify({ choice, at: Date.now() }))
  } catch {
    // private mode: they'll simply be asked again next time
  }
}

/** Show the card? Only when the browser would still ask, and "No thanks" wasn't said this month. */
export function shouldAsk(permission, stored, now = Date.now()) {
  if (permission === 'unknown' && stored?.choice === 'granted') return false // refresh quietly instead
  if (permission !== 'prompt' && permission !== 'unknown') return false
  return !(stored?.choice === 'declined' && now - stored.at < ASK_AGAIN_AFTER)
}

/** Already allowed: refresh the position without showing anything. */
export function shouldRefreshQuietly(permission, stored) {
  return permission === 'granted' || (permission === 'unknown' && stored?.choice === 'granted')
}

export function requestPosition() {
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lon: p.coords.longitude, accuracy: p.coords.accuracy }),
      reject,
      { enableHighAccuracy: true, timeout: 20_000, maximumAge: 5 * 60_000 },
    )
  })
}
