// The owner-only admin panel. Loaded lazily from App.jsx, so teammates never download it.
import { useCallback, useEffect, useState } from 'react'
import { cancelJob, getDashboard } from './adminApi'
import DeviceDrawer from './DeviceDrawer'
import DownloadsTab from './DownloadsTab'
import InsightsTab from './InsightsTab'
import OverviewTab from './OverviewTab'
import SecurityTab from './SecurityTab'
import SystemTab from './SystemTab'
import UsersTab from './UsersTab'
import './admin.css'

const TABS = [
  { id: 'overview', label: 'Overview', icon: 'fa-earth-asia' },
  { id: 'users', label: 'Users', icon: 'fa-users' },
  { id: 'downloads', label: 'Downloads', icon: 'fa-download' },
  { id: 'insights', label: 'Insights', icon: 'fa-chart-column' },
  { id: 'security', label: 'Security', icon: 'fa-shield-halved' },
  { id: 'system', label: 'System', icon: 'fa-server' },
]
const TAB_KEY = 'ez-admin-tab'
const POLL_MS = 5000
const STALE_MS = 20_000

function initialTab() {
  try {
    const saved = sessionStorage.getItem(TAB_KEY)
    return TABS.some((t) => t.id === saved) ? saved : 'overview'
  } catch {
    return 'overview'
  }
}

export default function AdminPanel({ theme, onToggleTheme, onOpenApp, onLogout, onSessionLost }) {
  const [tab, setTab] = useState(initialTab)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [deviceId, setDeviceId] = useState(null)
  const [updatedAt, setUpdatedAt] = useState(0)
  const [now, setNow] = useState(() => Date.now())

  const load = useCallback(async () => {
    try {
      setData(await getDashboard())
      setUpdatedAt(Date.now())
      setError(null)
    } catch (e) {
      if (e.status === 401) onSessionLost()
      else setError(e.message || 'Can’t reach the server')
    }
  }, [onSessionLost])

  // Refresh every 5 s while the tab is visible, and straight away when it comes back.
  useEffect(() => {
    load()
    const poll = setInterval(() => {
      setNow(Date.now())
      if (document.visibilityState === 'visible') load()
    }, POLL_MS)
    const onVisible = () => { if (document.visibilityState === 'visible') load() }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(poll)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [load])

  function pick(id) {
    setTab(id)
    try { sessionStorage.setItem(TAB_KEY, id) } catch { /* private mode */ }
  }

  async function cancel(jobId) {
    try {
      await cancelJob(jobId)
      load()
    } catch (e) {
      setError(e.message || 'Could not cancel that download')
    }
  }

  const stale = updatedAt && now - updatedAt > STALE_MS
  const health = error ? 'Can’t reach the server' : stale ? 'Reconnecting…' : 'Live'

  return (
    <div className="app admin">
      <div className="container">
        <header className="header">
          <div className="brand">
            <div className="logo" aria-hidden="true"><i className="fa-solid fa-user-shield" /></div>
            <div>
              <h1>EZ-Tube Admin</h1>
              <p className="muted">Who uses EZ-Tube, from where, and what they download</p>
            </div>
          </div>
          <div className="header-actions">
            <span className={`badge glass live ${error || stale ? 'warn' : 'ok'}`} role="status" title="Refreshes every 5 seconds">
              <span className="dot" />{health}
            </span>
            <button type="button" className="btn btn-sm" onClick={onOpenApp} title="Open the downloader">
              <i className="fa-solid fa-circle-down" /> Downloader
            </button>
            <button type="button" className="icon-btn glass" onClick={onToggleTheme}
              title={theme === 'light' ? 'Switch to night mode' : 'Switch to day mode'} aria-label="Toggle theme">
              <i className={theme === 'light' ? 'fa-solid fa-moon' : 'fa-solid fa-sun'} />
            </button>
            <button type="button" className="icon-btn glass" onClick={onLogout} title="Log out" aria-label="Log out">
              <i className="fa-solid fa-power-off" />
            </button>
          </div>
        </header>

        <nav className="admin-tabs" role="tablist" aria-label="Admin sections">
          {TABS.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id}
              className={`admin-tab ${tab === t.id ? 'active' : ''}`} onClick={() => pick(t.id)}>
              <i className={`fa-solid ${t.icon}`} aria-hidden="true" />
              {t.label}
              {t.id === 'overview' && data?.live.length > 0 && <span className="tab-count info">{data.live.length}</span>}
              {t.id === 'security' && data?.kpis.failedLogins24h > 0 && <span className="tab-count">{data.kpis.failedLogins24h}</span>}
            </button>
          ))}
        </nav>

        {error && <div className="error">{error}</div>}

        {!data ? <div className="loading muted">Loading the dashboard…</div> : (
          <main role="tabpanel" aria-label={TABS.find((t) => t.id === tab).label}>
            {tab === 'overview' && (
              <OverviewTab data={data} selectedId={deviceId} onSelectDevice={setDeviceId} onCancelJob={cancel} onShowTab={pick} />
            )}
            {tab === 'users' && <UsersTab devices={data.devices} you={data.you} onSelectDevice={setDeviceId} />}
            {tab === 'downloads' && <DownloadsTab devices={data.devices} onSelectDevice={setDeviceId} />}
            {tab === 'insights' && <InsightsTab onSelectDevice={setDeviceId} />}
            {tab === 'security' && <SecurityTab data={data} onSelectDevice={setDeviceId} onChanged={load} />}
            {tab === 'system' && <SystemTab data={data} onChanged={load} />}
          </main>
        )}
      </div>

      {deviceId && (
        <DeviceDrawer key={deviceId} id={deviceId} you={data?.you} onClose={() => setDeviceId(null)} onChanged={load} />
      )}

      <footer className="foot muted">EZ-Tube admin · only you can see this</footer>
    </div>
  )
}
