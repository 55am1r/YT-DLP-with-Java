import { useEffect, useState } from 'react'
import { getYtdlpStatus } from '../api'

export default function Header({ theme, onToggleTheme, onLogout }) {
  const [status, setStatus] = useState(null)
  const [checking, setChecking] = useState(false)

  useEffect(() => {
    let alive = true
    const load = () => getYtdlpStatus().then((s) => alive && setStatus(s)).catch(() => {})
    load()
    const t = setInterval(load, 10000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [])

  async function refresh() {
    setChecking(true)
    try {
      setStatus(await getYtdlpStatus(true))
    } catch {
      /* ignore */
    } finally {
      setChecking(false)
    }
  }

  const ver = status?.installed
  const ok = status?.upToDate
  const cls = checking ? 'warn' : ok ? 'ok' : ver ? 'warn' : ''

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
          title={status?.message || 'Check for a yt-dlp update'}
        >
          <span className="dot" />
          {checking ? 'Checking…' : ver ? `yt-dlp ${ver}` : 'yt-dlp'}
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
      </div>
    </header>
  )
}
