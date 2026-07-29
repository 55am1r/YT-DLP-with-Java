import { useEffect, useRef, useState } from 'react'
import { getYtdlpStatus } from '../api'

const JUST_UPDATED_MS = 6000

export default function Header({ theme, onToggleTheme, onLogout, onOpenDownloads, downloadCount }) {
  const [status, setStatus] = useState(null)
  const [checking, setChecking] = useState(false)
  // Show "Updated!" briefly after a refresh actually bumps the installed version, so
  // a manual click on the badge has visible feedback.
  const [justUpdated, setJustUpdated] = useState(false)
  const prevVersion = useRef(null)
  const clearTimer = useRef(null)

  useEffect(() => {
    let alive = true
    const load = () => getYtdlpStatus().then((s) => alive && setStatus(s)).catch(() => {})
    load()
    const t = setInterval(load, 10000)
    return () => { alive = false; clearInterval(t); clearTimeout(clearTimer.current) }
  }, [])

  async function refresh() {
    setChecking(true)
    const before = status?.installed
    try {
      const next = await getYtdlpStatus(true)
      setStatus(next)
      if (before && next?.installed && next.installed !== before) {
        setJustUpdated(true)
        clearTimeout(clearTimer.current)
        clearTimer.current = setTimeout(() => setJustUpdated(false), JUST_UPDATED_MS)
      }
    } catch { /* ignore */ }
    finally { setChecking(false) }
  }

  // Track the version quietly so a background poll that catches an update also
  // triggers the "Updated!" flash, not just a manual click.
  useEffect(() => {
    const v = status?.installed
    if (v && prevVersion.current && prevVersion.current !== v) {
      setJustUpdated(true)
      clearTimeout(clearTimer.current)
      clearTimer.current = setTimeout(() => setJustUpdated(false), JUST_UPDATED_MS)
    }
    if (v) prevVersion.current = v
  }, [status?.installed])

  const ver = status?.installed
  const ok = status?.upToDate
  // Four possible messages, mutually exclusive and prioritised in this order.
  const label =
    checking       ? 'Checking for an update…'
    : justUpdated  ? 'Updated!'
    : ok           ? 'Up-to-date'
    : ver          ? 'Update available'
    :                'Checking…'
  const cls =
    checking       ? 'warn'
    : justUpdated  ? 'ok'
    : ok           ? 'ok'
    : ver          ? 'warn'
    :                ''
  const tooltip = status?.message
    || (ver ? `yt-dlp ${ver}${ok ? ' — latest' : ' — click to update'}` : 'Check for a yt-dlp update')

  return (
    <header className="header">
      <div className="brand">
        <div className="logo" aria-hidden="true"><i className="fa-solid fa-circle-down" /></div>
        <div>
          <h1>EZ-Tube</h1>
          <p className="muted">Download audio &amp; video from YouTube — for the team</p>
        </div>
      </div>

      {/* Fixed order at every screen size: update check, theme, log out. */}
      <div className="header-actions">
        <button
          className={`badge glass ${cls}`}
          onClick={refresh}
          disabled={checking}
          title={tooltip}
        >
          <span className="dot" />
          {label}
        </button>
        <button
          className="icon-btn glass"
          onClick={onToggleTheme}
          title={theme === 'light' ? 'Switch to night mode' : 'Switch to day mode'}
          aria-label="Toggle theme"
        >
          <i className={theme === 'light' ? 'fa-solid fa-moon' : 'fa-solid fa-sun'} />
        </button>
        <button className="icon-btn glass" onClick={onLogout} title="Log out" aria-label="Log out">
          <i className="fa-solid fa-power-off" />
        </button>
        {/* Downloads shortcut — mobile only. On desktop the downloads column already
            sits beside the panel, so there is nothing to open. */}
        {onOpenDownloads && (
          <button
            className="icon-btn glass mobile-only"
            onClick={onOpenDownloads}
            title="Show downloads"
            aria-label="Show downloads"
          >
            <i className="fa-solid fa-ellipsis-vertical" />
            {downloadCount > 0 && <span className="icon-btn-badge">{downloadCount}</span>}
          </button>
        )}
      </div>
    </header>
  )
}
