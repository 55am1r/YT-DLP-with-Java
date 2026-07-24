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

/** "1080p · MP4" for a finished job. */
export function fmtKind(job) {
  const parts = []
  if (job.height) parts.push(`${job.height}p`)
  if (job.container) parts.push(job.container.toUpperCase())
  return parts.join(' · ')
}
