import { useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import { setIpBlocked, unlockIp, updateDevice } from './adminApi'
import { Chip, DeviceLink, Empty, Kpi } from './AdminBits'
import { EVENT, fmtDateTime, timeAgo } from './format'
import Pager from './Pager'
import { usePaged } from './paging'
import { useEventLog } from './useEventLog'

/** Wrong logins, lockouts, and what the admin has blocked — with the controls to change it. */
export default function SecurityTab({ data, tick, onSelectDevice, onChanged }) {
  const [ip, setIp] = useState('')
  const [error, setError] = useState(null)
  const [confirm, setConfirm] = useState(null)
  const [busy, setBusy] = useState(false)

  const locked = Object.entries(data.lockedIps || {})
  const blockedDevices = data.devices.filter((d) => d.blocked)
  const blocked = [
    ...data.blockedIps.map((b) => ({ key: `ip:${b}`, ip: b })),
    ...blockedDevices.map((d) => ({ key: `device:${d.id}`, device: d })),
  ]
  const [lockedShown, lockedPager] = usePaged(locked, { id: 'security-locked' })
  const [blockedShown, blockedPager] = usePaged(blocked, { id: 'security-blocked' })
  const log = useEventLog('security', { id: 'security-log', tick })

  async function act(fn) {
    setBusy(true)
    setError(null)
    try {
      await fn()
      onChanged()
      return true
    } catch (e) {
      setError(e.message || 'That didn’t work')
      return false
    } finally {
      setBusy(false)
    }
  }

  function submitBlock(e) {
    e.preventDefault()
    if (ip.trim()) setConfirm(ip.trim())
  }

  return (
    <>
      <div className="kpi-row">
        <Kpi label="Wrong logins, 24 h" value={data.kpis.failedLogins24h} tone={data.kpis.failedLogins24h ? 'warning' : undefined} />
        <Kpi label="Locked out now" value={locked.length} sub="10 wrong in 15 min → 10 min lock" />
        <Kpi label="Blocked IPs" value={data.blockedIps.length} />
        <Kpi label="Blocked devices" value={blockedDevices.length} />
      </div>

      {error && <div className="error">{error}</div>}

      <div className="admin-cols">
        <section className="panel glass">
          <div className="panel-head"><h2>Locked out right now</h2></div>
          {locked.length ? (
            <>
              <ul className="plain-list" ref={lockedPager.topRef}>
                {lockedShown.map(([lockedIp, until]) => (
                  <li key={lockedIp}>
                    <span className="mono">{lockedIp}</span>
                    <span className="muted small">until {fmtDateTime(until)}</span>
                    <button type="button" className="link-btn" disabled={busy} onClick={() => act(() => unlockIp(lockedIp))}>Unlock</button>
                  </li>
                ))}
              </ul>
              <Pager pager={lockedPager} noun="addresses" />
            </>
          ) : <Empty icon="fa-lock-open" title="Nobody is locked out" />}
        </section>

        <section className="panel glass">
          <div className="panel-head"><h2>Blocked</h2></div>
          <form className="inline-form" onSubmit={submitBlock}>
            <input className="input input-sm mono" placeholder="IP address, e.g. 203.0.113.7" value={ip}
              onChange={(e) => setIp(e.target.value)} aria-label="IP address to block" />
            <button type="submit" className="btn btn-sm btn-danger" disabled={busy || !ip.trim()}>
              <i className="fa-solid fa-ban" /> Block IP
            </button>
          </form>
          <p className="muted small">Blocking an IP turns away everyone behind it — on a shared office network, that’s everyone there.</p>
          {blocked.length ? (
            <>
              <ul className="plain-list" ref={blockedPager.topRef}>
                {blockedShown.map((b) => (b.ip ? (
                  <li key={b.key}>
                    <Chip tone="critical" icon="fa-network-wired">IP</Chip>
                    <span className="mono">{b.ip}</span>
                    <button type="button" className="link-btn" disabled={busy} onClick={() => act(() => setIpBlocked(b.ip, false))}>Unblock</button>
                  </li>
                ) : (
                  <li key={b.key}>
                    <Chip tone="critical" icon="fa-ban">Device</Chip>
                    <DeviceLink id={b.device.id} name={b.device.name} onSelect={onSelectDevice} />
                    <button type="button" className="link-btn" disabled={busy} onClick={() => act(() => updateDevice(b.device.id, { blocked: false }))}>Unblock</button>
                  </li>
                )))}
              </ul>
              <Pager pager={blockedPager} noun="blocks" />
            </>
          ) : <Empty icon="fa-circle-check" title="Nothing is blocked" />}
        </section>
      </div>

      <section className="panel glass">
        <div className="panel-head">
          <h2>Security log</h2>
          <span className="muted small">
            {log.total ? `${log.total.toLocaleString()} ${log.total === 1 ? 'entry' : 'entries'} — wrong logins, lockouts, admin sign-ins and admin actions` : ''}
          </span>
        </div>
        {log.error && <div className="error">{log.error}</div>}
        {!log.loaded ? <p className="muted">Loading…</p> : log.items.length ? (
          <>
          <div className="table-wrap" ref={log.pager.topRef}>
            <table className="admin-table">
              <thead>
                <tr><th>When</th><th>What</th><th>Who</th><th>From</th><th>Details</th></tr>
              </thead>
              <tbody>
                {log.items.map((e, i) => {
                  const m = EVENT[e.type] || { label: e.type, icon: 'fa-circle', tone: 'muted' }
                  return (
                    <tr key={`${e.at}-${i}`}>
                      <td title={new Date(e.at).toLocaleString()}>{timeAgo(e.at)}</td>
                      <td><Chip tone={m.tone} icon={m.icon}>{m.label}</Chip></td>
                      <td>
                        {e.deviceId && e.deviceName !== 'Unknown device'
                          ? <DeviceLink id={e.deviceId} name={e.deviceName} onSelect={onSelectDevice} />
                          : <span className="muted">New device</span>}
                      </td>
                      <td><span className="mono">{e.ip || '—'}</span>{e.place && <span className="muted small block">{e.place}</span>}</td>
                      <td>{e.detail || ''}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pager pager={log.pager} noun={log.total === 1 ? 'entry' : 'entries'} />
          </>
        ) : <Empty icon="fa-shield-halved" title="Nothing to report" />}
      </section>

      {confirm && (
        <ConfirmDialog
          title={`Block ${confirm}?`}
          message="Every device using this address is turned away with “Access blocked by the admin.”"
          detail="You can unblock it here at any time."
          cancelLabel="Never mind" confirmLabel="Block IP" headerIcon="fa-ban" confirmIcon="fa-ban"
          onCancel={() => setConfirm(null)}
          onConfirm={async () => {
            const target = confirm
            setConfirm(null)
            if (await act(() => setIpBlocked(target, true))) setIp('')
          }}
        />
      )}
    </>
  )
}
