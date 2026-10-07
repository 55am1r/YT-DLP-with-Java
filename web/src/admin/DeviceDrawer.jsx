import { useCallback, useEffect, useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import { getDevice, setIpBlocked, updateDevice } from './adminApi'
import { Chip, DownloadRow, Empty } from './AdminBits'
import { ActivityFeed } from './OverviewTab'
import { bytes, CONSENT, deviceIcon, fmtDateTime, gmapsLink, osmLink, timeAgo, VIA } from './format'

const SLIDE_OUT_MS = 560

/** Everything about one device, slid in from the right. Refreshes itself every 10 s. */
export default function DeviceDrawer({ id, you, onClose, onChanged }) {
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)
  const [nick, setNick] = useState(null) // null = not renaming
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(null) // { kind: 'device' } | { kind: 'ip', ip }
  const [closing, setClosing] = useState(false)

  const load = useCallback(async () => {
    try {
      setDetail(await getDevice(id))
      setError(null)
    } catch (e) {
      setError(e.message || 'Could not load this device')
    }
  }, [id])

  useEffect(() => {
    load()
    const t = setInterval(load, 10_000)
    return () => clearInterval(t)
  }, [load])

  const close = useCallback(() => {
    setClosing(true)
    setTimeout(onClose, SLIDE_OUT_MS)
  }, [onClose])

  useEffect(() => {
    // While a confirm dialog is up, Escape is its to handle — not a reason to close the drawer.
    if (confirm) return undefined
    const onKey = (e) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [close, confirm])

  async function change(fn) {
    setBusy(true)
    setError(null)
    try {
      await fn()
      await load()
      onChanged()
      return true
    } catch (e) {
      setError(e.message || 'That didn’t work')
      return false
    } finally {
      setBusy(false)
    }
  }

  async function saveNick(e) {
    e.preventDefault()
    if (await change(() => updateDevice(id, { nickname: nick.trim() }))) setNick(null)
  }

  const d = detail?.device
  const consent = d && (CONSENT[d.consent] || CONSENT.unknown)

  return (
    <>
      <div className={`sheet-backdrop ${closing ? 'closing' : ''}`} onClick={close} role="presentation">
        <aside
          className={`sheet glass drawer ${closing ? 'closing' : ''}`} role="dialog" aria-label="Device details"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="sheet-head">
            <button type="button" className="icon-btn glass" onClick={close} aria-label="Close" title="Close">
              <i className="fa-solid fa-arrow-right" />
            </button>
            <span className="sheet-title">Device</span>
          </div>

          <div className="drawer-body">
            {error && <div className="error">{error}</div>}
            {!d ? <p className="muted">Loading…</p> : (
              <>
                <div className="drawer-id">
                  <i className={`fa-solid ${deviceIcon(d.deviceType)} drawer-icon`} aria-hidden="true" />
                  <div className="drawer-name">
                    {nick == null ? (
                      <h3>
                        {d.name}{' '}
                        <button type="button" className="link-btn small" onClick={() => setNick(d.nickname || '')}>
                          {d.nickname ? 'Rename' : 'Name it'}
                        </button>
                      </h3>
                    ) : (
                      <form className="nick-form" onSubmit={saveNick}>
                        <input
                          className="input input-sm" value={nick} maxLength={40} autoFocus
                          placeholder="e.g. Ravi’s iPhone" aria-label="Name for this device"
                          onChange={(e) => setNick(e.target.value)}
                        />
                        <button type="submit" className="btn btn-sm btn-primary" disabled={busy}>Save</button>
                        <button type="button" className="btn btn-sm" onClick={() => setNick(null)}>Cancel</button>
                      </form>
                    )}
                    <p className="muted small">{d.label} · first seen {fmtDateTime(d.firstSeen)}</p>
                  </div>
                </div>

                <div className="chip-row">
                  {d.online
                    ? <Chip tone="good" icon="fa-circle">Online now</Chip>
                    : <Chip icon="fa-clock">Seen {timeAgo(d.lastSeen)}</Chip>}
                  <Chip tone={consent.tone} icon={consent.icon}>{consent.label}</Chip>
                  {d.id === you && <Chip tone="info" icon="fa-user-shield">This is you</Chip>}
                  {d.admin && d.id !== you && <Chip tone="info" icon="fa-user-shield">Has opened the admin panel</Chip>}
                  {d.blocked && <Chip tone="critical" icon="fa-ban">Blocked</Chip>}
                  {d.vpnHint && (
                    <Chip tone="warning" icon="fa-user-secret" title="The browser’s time zone doesn’t match where its IP address is">
                      Possible VPN
                    </Chip>
                  )}
                </div>

                <h4 className="section-title">Location</h4>
                <dl className="facts">
                  {d.precise && (
                    <>
                      <dt>Precise</dt>
                      <dd>
                        <span className="mono">{d.precise.lat.toFixed(5)}, {d.precise.lon.toFixed(5)}</span>
                        {d.precise.accuracy != null && <span className="muted"> ±{Math.round(d.precise.accuracy)} m</span>}
                        <span className="muted small block">
                          shared {timeAgo(d.precise.at)} ·{' '}
                          <a href={osmLink(d.precise.lat, d.precise.lon)} target="_blank" rel="noopener noreferrer">OpenStreetMap</a>
                          {' · '}
                          <a href={gmapsLink(d.precise.lat, d.precise.lon)} target="_blank" rel="noopener noreferrer">Google Maps</a>
                        </span>
                      </dd>
                    </>
                  )}
                  <dt>{d.ipGeo?.approximate ? 'Network' : 'From IP'}</dt>
                  <dd>
                    {d.ipGeo ? (
                      <>
                        {d.ipGeo.approximate ? 'Office network, at the Mac — ' : ''}
                        {[d.ipGeo.city, d.ipGeo.region, d.ipGeo.country].filter(Boolean).join(', ') || 'Unknown'}
                        <span className="muted small block">
                          {[!d.ipGeo.approximate && d.ipGeo.isp, d.ipGeo.timezone].filter(Boolean).join(' · ')}
                        </span>
                      </>
                    ) : <span className="muted">Looking it up…</span>}
                  </dd>
                  <dt>Browser time zone</dt>
                  <dd>{d.timezone || '—'}</dd>
                </dl>

                <h4 className="section-title">Device</h4>
                <dl className="facts">
                  <dt>System</dt>
                  <dd>{[d.os, d.browser].filter(Boolean).join(' · ') || '—'}{d.deviceType ? ` (${d.deviceType})` : ''}</dd>
                  <dt>Screen</dt>
                  <dd>{d.screen || '—'}</dd>
                  <dt>Language</dt>
                  <dd>{d.language || '—'}</dd>
                  <dt>Visits</dt>
                  <dd>{d.visits.toLocaleString()}</dd>
                  <dt>Downloads</dt>
                  <dd>{d.downloads.toLocaleString()} · {bytes(d.bytes)}{d.active ? ` · ${d.active} running` : ''}</dd>
                </dl>

                <h4 className="section-title">Networks used</h4>
                <div className="table-wrap">
                  <table className="admin-table compact">
                    <thead>
                      <tr><th>IP address</th><th>Route</th><th>Last used</th><th className="num">Requests</th><th aria-label="Actions" /></tr>
                    </thead>
                    <tbody>
                      {[...d.ips].sort((a, b) => b.lastSeen - a.lastSeen).map((ip) => (
                        <tr key={ip.ip}>
                          <td className="mono">{ip.ip}</td>
                          <td className="muted">{ip.ip === d.ip ? (VIA[d.via] || d.via) : ''}</td>
                          <td title={`First used ${fmtDateTime(ip.firstSeen)}`}>{timeAgo(ip.lastSeen)}</td>
                          <td className="num">{ip.hits.toLocaleString()}</td>
                          <td>
                            {d.id !== you && (
                              <button type="button" className="link-btn danger small" onClick={() => setConfirm({ kind: 'ip', ip: ip.ip })}>
                                Block IP
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <h4 className="section-title">Downloads ({detail.downloads.length})</h4>
                {detail.downloads.length
                  ? <ul className="dl-list">{detail.downloads.map((r) => <DownloadRow key={r.jobId} r={r} showDevice={false} onSelectDevice={() => {}} />)}</ul>
                  : <Empty icon="fa-download" title="Nothing downloaded yet" />}

                <h4 className="section-title">Activity</h4>
                <ActivityFeed events={detail.events.slice(0, 40)} onSelectDevice={() => {}} />

                {d.id !== you && (
                  <div className="drawer-actions">
                    <button
                      type="button" className={`btn btn-sm ${d.blocked ? '' : 'btn-danger'}`} disabled={busy}
                      onClick={() => setConfirm({ kind: 'device' })}
                    >
                      <i className={`fa-solid ${d.blocked ? 'fa-unlock' : 'fa-ban'}`} />
                      {d.blocked ? 'Unblock this device' : 'Block this device'}
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        </aside>
      </div>

      {confirm?.kind === 'device' && d && (
        <ConfirmDialog
          title={d.blocked ? 'Unblock this device?' : 'Block this device?'}
          message={d.blocked
            ? `${d.name} will be able to use EZ-Tube again.`
            : `${d.name} will be turned away on every page with “Access blocked by the admin.”`}
          detail={d.blocked ? '' : 'Someone could get round it by clearing their browser data — block the IP too if you need to.'}
          cancelLabel="Never mind" confirmLabel={d.blocked ? 'Unblock' : 'Block'}
          headerIcon={d.blocked ? 'fa-unlock' : 'fa-ban'} confirmIcon={d.blocked ? 'fa-unlock' : 'fa-ban'}
          onCancel={() => setConfirm(null)}
          onConfirm={() => { setConfirm(null); change(() => updateDevice(id, { blocked: !d.blocked })) }}
        />
      )}
      {confirm?.kind === 'ip' && (
        <ConfirmDialog
          title={`Block ${confirm.ip}?`}
          message="Every device using this address is turned away — on a shared network, that can be a whole office."
          detail="You can unblock it from the Security tab."
          cancelLabel="Never mind" confirmLabel="Block IP" headerIcon="fa-ban" confirmIcon="fa-ban"
          onCancel={() => setConfirm(null)}
          onConfirm={() => { const ip = confirm.ip; setConfirm(null); change(() => setIpBlocked(ip, true)) }}
        />
      )}
    </>
  )
}
