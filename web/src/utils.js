export function fmtDuration(seconds) {
  if (seconds == null) return ''
  const s = Math.round(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

export function fmtSize(bytes) {
  if (!bytes) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let n = bytes
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(n < 10 && i > 0 ? 1 : 0)} ${units[i]}`
}

/** "3.4s" / "1m 12s" — how long the server took. */
export function fmtElapsed(ms) {
  if (!ms || ms < 0) return ''
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(1)}s`
  const m = Math.floor(s / 60)
  return `${m}m ${Math.round(s % 60)}s`
}

/** "1:59:04" remaining until the server deletes the file. */
export function fmtCountdown(msLeft) {
  if (msLeft == null || msLeft <= 0) return '0:00'
  const t = Math.floor(msLeft / 1000)
  const h = Math.floor(t / 3600)
  const m = Math.floor((t % 3600) / 60)
  const s = t % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

/** Seconds → "1:05" / "1:02:03", used for slider labels. */
export function secondsToClock(sec) {
  const t = Math.max(0, Math.round(sec || 0))
  const h = Math.floor(t / 3600)
  const m = Math.floor((t % 3600) / 60)
  const s = t % 60
  const ss = String(s).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** Accepts 90, "90", "1m30s", "1h2m3s", "1:30", "01:02:03". */
export function parseTimeToSeconds(raw) {
  if (raw == null) return null
  const s = String(raw).trim()
  if (!s) return null
  if (/^\d+$/.test(s)) return parseInt(s, 10)
  if (s.includes(':')) {
    const parts = s.split(':').map((n) => parseInt(n, 10) || 0).reverse()
    return (parts[0] || 0) + (parts[1] || 0) * 60 + (parts[2] || 0) * 3600
  }
  const m = s.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/i)
  if (m && (m[1] || m[2] || m[3])) {
    return (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0))
  }
  return null
}

/**
 * Pull a start time out of a pasted link — YouTube uses ?t=90, &start=90 or #t=1m30s.
 * Returns seconds, or null when the link carries no timestamp.
 */
export function parseUrlTimestamp(url) {
  try {
    const u = new URL(url)
    const raw =
      u.searchParams.get('t') ||
      u.searchParams.get('start') ||
      (u.hash.match(/t=([^&]+)/) || [])[1]
    return raw ? parseTimeToSeconds(raw) : null
  } catch {
    return null
  }
}

/** "1080p · MP4" for a finished job. */
export function fmtKind(job) {
  const parts = []
  if (job.height) parts.push(`${job.height}p`)
  if (job.container) parts.push(job.container.toUpperCase())
  return parts.join(' · ')
}
