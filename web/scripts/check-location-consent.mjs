// When does the downloader offer the "share your location?" card? Run:
//   node scripts/check-location-consent.mjs
import { shouldAsk, shouldRefreshQuietly } from '../src/telemetry.js'

const DAY = 86_400_000
const now = Date.UTC(2026, 9, 7)
let failures = 0
const expect = (want, permission, stored, why) => {
  const got = shouldAsk(permission, stored, now)
  if (got !== want) { failures++; console.error(`FAIL ${why}: shouldAsk(${permission}, ${JSON.stringify(stored)}) = ${got}`) }
}

expect(true, 'prompt', null, 'never asked → ask')
expect(false, 'granted', null, 'already allowed → refresh quietly, no card')
expect(false, 'denied', null, 'blocked in the browser → never nag')
expect(false, 'unsupported', null, 'LAN http:// or no GPS → cannot ask')
expect(false, 'prompt', { choice: 'declined', at: now - 3 * DAY }, '"No thanks" 3 days ago → leave them be')
expect(true, 'prompt', { choice: 'declined', at: now - 31 * DAY }, '"No thanks" over 30 days ago → ask once more')
expect(true, 'prompt', { choice: 'granted', at: now - DAY }, 'agreed before but the browser forgot → ask, never prompt cold')
expect(false, 'unknown', { choice: 'granted', at: now - DAY }, 'no Permissions API but agreed before → refresh quietly')
expect(true, 'unknown', null, 'no Permissions API, never asked → ask')
expect(false, 'unknown', { choice: 'declined', at: now - DAY }, 'no Permissions API, declined recently → leave them be')

const quiet = (want, permission, stored, why) => {
  const got = shouldRefreshQuietly(permission, stored)
  if (got !== want) { failures++; console.error(`FAIL ${why}: shouldRefreshQuietly(${permission}, ${JSON.stringify(stored)}) = ${got}`) }
}
quiet(true, 'granted', { choice: 'granted', at: now }, 'agreed and allowed → refresh quietly')
quiet(true, 'unknown', { choice: 'granted', at: now }, 'agreed, no Permissions API → refresh quietly')
quiet(false, 'granted', { choice: 'declined', at: now }, 'pressed "Stop sharing" → never re-send, even though the browser still allows it')
quiet(false, 'prompt', { choice: 'granted', at: now }, 'browser forgot → ask with the card instead')

if (failures) process.exit(1)
console.log('location consent rules OK')
