import { useEffect, useState } from 'react'
import { getAnnouncement } from '../api'

const DISMISS_KEY = 'ez-announcement-dismissed'
const POLL_MS = 60_000

function dismissedAt() {
  try {
    return Number(localStorage.getItem(DISMISS_KEY)) || 0
  } catch {
    return 0
  }
}

/** The admin's message to the team. Dismissing hides this message only — a new one shows again. */
export default function AnnouncementBanner() {
  const [announcement, setAnnouncement] = useState(null)
  const [dismissed, setDismissed] = useState(dismissedAt)

  useEffect(() => {
    let alive = true
    const load = () => getAnnouncement().then((a) => { if (alive) setAnnouncement(a) }).catch(() => {})
    load()
    const t = setInterval(load, POLL_MS)
    return () => { alive = false; clearInterval(t) }
  }, [])

  function dismiss() {
    setDismissed(announcement.at)
    try { localStorage.setItem(DISMISS_KEY, String(announcement.at)) } catch { /* private mode */ }
  }

  if (!announcement || announcement.at === dismissed) return null
  const warn = announcement.level === 'warn'
  return (
    <div className={`notice glass announcement ${warn ? 'warn' : ''}`} role="status">
      <div className="notice-icon" aria-hidden="true">
        <i className={`fa-solid ${warn ? 'fa-triangle-exclamation' : 'fa-bullhorn'}`} />
      </div>
      <div className="notice-body">
        <strong>From the admin</strong>
        <span>{announcement.text}</span>
      </div>
      <button type="button" className="icon-btn glass" onClick={dismiss} aria-label="Dismiss" title="Dismiss">
        <i className="fa-solid fa-xmark" />
      </button>
    </div>
  )
}
