// Wording and number formatting shared by the admin tabs.
import { fmtSize } from '../utils'

export { fmtSize }

const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

const dateFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
const dayFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

export const fmtDate = (ms) => (ms ? dateFmt.format(new Date(ms)) : '—')
export const fmtDateTime = (ms) => (ms ? dateTimeFmt.format(new Date(ms)) : '—')
/** "7 Oct" for a "2026-10-07" day key. */
export const fmtDay = (iso) => dayFmt.format(new Date(`${iso}T12:00:00`))

export function timeAgo(ms, now = Date.now()) {
  if (!ms) return '—'
  const d = now - ms
  if (d < 45_000) return 'just now'
  if (d < HOUR) return `${Math.max(1, Math.round(d / MIN))} min ago`
  if (d < DAY) return `${Math.round(d / HOUR)} h ago`
  if (d < 2 * DAY) return 'yesterday'
  if (d < 30 * DAY) return `${Math.round(d / DAY)} days ago`
  return fmtDate(ms)
}

export function fmtDuration(ms) {
  if (!ms || ms < 0) return '—'
  const d = Math.floor(ms / DAY)
  const h = Math.floor((ms % DAY) / HOUR)
  const m = Math.floor((ms % HOUR) / MIN)
  if (d) return `${d} d ${h} h`
  if (h) return `${h} h ${m} min`
  return `${m} min`
}

/** 1,284 · 12.9K · 3.4M — stat-tile figures. */
export function compact(n) {
  if (n == null) return '—'
  if (n < 10_000) return n.toLocaleString()
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 100_000 ? 1 : 0)}K`
  return `${(n / 1_000_000).toFixed(1)}M`
}

export const bytes = (n) => fmtSize(n) || '0 B'

/** A link only for http(s) — a teammate's URL is untrusted and javascript: must never become a link. */
export const safeHref = (url) => (typeof url === 'string' && /^https?:\/\//i.test(url) ? url : null)

export const ytThumb = (id) => (id ? `https://i.ytimg.com/vi/${id}/mqdefault.jpg` : null)

export const osmLink = (lat, lon) => `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=16/${lat}/${lon}`
export const gmapsLink = (lat, lon) => `https://www.google.com/maps?q=${lat},${lon}`

export const VIA = { internet: 'Internet', lan: 'Office LAN', local: 'This Mac' }

export const SOURCE = {
  precise: { label: 'Precise — shared by the device', short: 'Precise' },
  ip: { label: 'Approximate — from the IP address', short: 'From IP' },
  lan: { label: 'Office network — the Mac’s own location', short: 'Office LAN' },
}

export const CONSENT = {
  granted: { label: 'Shares precise location', tone: 'good', icon: 'fa-location-crosshairs' },
  declined: { label: 'Declined to share location', tone: 'muted', icon: 'fa-location-pin-lock' },
  denied: { label: 'Location blocked in the browser', tone: 'muted', icon: 'fa-ban' },
  unavailable: { label: 'Can’t be asked on the LAN link', tone: 'muted', icon: 'fa-wifi' },
  unknown: { label: 'Not asked yet', tone: 'muted', icon: 'fa-circle-question' },
}

export function statusMeta(status) {
  switch (status) {
    case 'COMPLETED': return { label: 'Completed', tone: 'good', icon: 'fa-circle-check' }
    case 'FAILED': return { label: 'Failed', tone: 'critical', icon: 'fa-circle-xmark' }
    case 'CANCELED': return { label: 'Canceled', tone: 'muted', icon: 'fa-circle-minus' }
    case 'PAUSED': return { label: 'Paused', tone: 'warning', icon: 'fa-circle-pause' }
    case 'QUEUED': return { label: 'Waiting', tone: 'info', icon: 'fa-hourglass-half' }
    case 'ACTIVE': return { label: 'In progress', tone: 'info', icon: 'fa-arrows-rotate' }
    default: return { label: 'In progress', tone: 'info', icon: 'fa-arrows-rotate' }
  }
}

export const EVENT = {
  LOGIN: { label: 'Signed in', icon: 'fa-right-to-bracket', tone: 'muted' },
  ADMIN_LOGIN: { label: 'Admin signed in', icon: 'fa-user-shield', tone: 'info' },
  LOGIN_FAILED: { label: 'Wrong login', icon: 'fa-triangle-exclamation', tone: 'warning' },
  LOCKED_OUT: { label: 'Locked out', icon: 'fa-lock', tone: 'critical' },
  LOGOUT: { label: 'Signed out', icon: 'fa-right-from-bracket', tone: 'muted' },
  ANALYZE: { label: 'Looked up', icon: 'fa-magnifying-glass', tone: 'muted' },
  LOCATION: { label: 'Location', icon: 'fa-location-dot', tone: 'muted' },
  ADMIN_ACTION: { label: 'Admin action', icon: 'fa-gavel', tone: 'info' },
}

export function deviceIcon(type) {
  if (type === 'phone') return 'fa-mobile-screen'
  if (type === 'tablet') return 'fa-tablet-screen-button'
  return 'fa-desktop'
}

/** "MP3" · "MP4 · 2160p" · "MP4 · 2160p · clip 1:00–2:30" · "Playlist · 12 items". */
export function formatLabel(r) {
  const parts = []
  if (r.format) parts.push(r.format.toUpperCase())
  const q = r.qualityLabel || (r.kind === 'video' && r.height ? `${r.height}p` : null)
  if (q) parts.push(q)
  if (r.playlist) parts.push(r.itemCount ? `playlist · ${r.itemCount} items` : 'playlist')
  if (r.clip) parts.push(`clip ${r.clip}`)
  return parts.join(' · ')
}
