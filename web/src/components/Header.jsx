import { useEffect, useState } from 'react'
import { getYtdlpStatus } from '../api'

export default function Header() {
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
  const title = status?.message || 'Click to check for a yt-dlp update'

  return (
    <header className="header">
      <div className="brand">
        <div className="logo" aria-hidden="true">▼</div>
        <div>
          <h1>EZ-Tube</h1>
          <p className="muted">Download audio &amp; video from YouTube — for the team</p>
        </div>
      </div>
      <button className={`badge ${cls}`} onClick={refresh} disabled={checking} title={title}>
        <span className="dot" />
        {checking ? 'Checking yt-dlp…' : ver ? `yt-dlp ${ver}${ok ? '' : ' · update ⟳'}` : 'yt-dlp'}
      </button>
    </header>
  )
}
