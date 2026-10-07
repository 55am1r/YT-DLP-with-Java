import { useState } from 'react'
import { Chip, Empty } from './AdminBits'
import { bytes, deviceIcon, SOURCE, timeAgo, VIA } from './format'

const SHOW = [
  { id: 'all', label: 'All', test: () => true },
  { id: 'online', label: 'Online', test: (d) => d.online },
  { id: 'precise', label: 'Shared location', test: (d) => d.place?.source === 'precise' },
  { id: 'blocked', label: 'Blocked', test: (d) => d.blocked },
]

const SORTS = {
  recent: (a, b) => b.lastSeen - a.lastSeen,
  downloads: (a, b) => b.downloads - a.downloads || b.lastSeen - a.lastSeen,
  name: (a, b) => a.name.localeCompare(b.name),
}

/** Every device as a table — also the map's text equivalent. */
export default function UsersTab({ devices, you, onSelectDevice }) {
  const [q, setQ] = useState('')
  const [show, setShow] = useState('all')
  const [sort, setSort] = useState('recent')

  const needle = q.trim().toLowerCase()
  const test = SHOW.find((s) => s.id === show).test
  const rows = devices
    .filter(test)
    .filter((d) => !needle || [d.name, d.label, d.ip, d.place?.label, d.ipGeo?.isp]
      .some((v) => v && v.toLowerCase().includes(needle)))
    .sort(SORTS[sort])

  return (
    <section className="panel glass">
      <div className="toolbar">
        <input
          className="input input-sm" type="search" placeholder="Search name, place, IP or ISP"
          value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search devices"
        />
        <div className="seg seg-sm glass" role="group" aria-label="Show">
          {SHOW.map((s) => (
            <button key={s.id} type="button" className={`seg-btn ${show === s.id ? 'active' : ''}`}
              aria-pressed={show === s.id} onClick={() => setShow(s.id)}>
              {s.label}
            </button>
          ))}
        </div>
        <label className="select-wrap">
          <span className="muted small">Sort</span>
          <select className="select" value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="recent">Last seen</option>
            <option value="downloads">Most downloads</option>
            <option value="name">Name</option>
          </select>
        </label>
      </div>

      {rows.length ? (
        <div className="table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>Device</th>
                <th>Seen</th>
                <th>Location</th>
                <th>Network</th>
                <th className="num">Downloads</th>
                <th className="num">Data</th>
                <th className="num">Visits</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <tr key={d.id} className="clickable" onClick={() => onSelectDevice(d.id)}>
                  <td>
                    <button type="button" className="row-main" onClick={(e) => { e.stopPropagation(); onSelectDevice(d.id) }}>
                      <i className={`fa-solid ${deviceIcon(d.deviceType)}`} aria-hidden="true" />
                      <span>
                        <strong>{d.name}</strong>
                        {d.nickname && <span className="muted small block">{d.label}</span>}
                      </span>
                    </button>
                    <span className="chip-row">
                      {d.id === you && <Chip tone="info" icon="fa-user-shield">You</Chip>}
                      {d.blocked && <Chip tone="critical" icon="fa-ban">Blocked</Chip>}
                    </span>
                  </td>
                  <td>
                    {d.online
                      ? <Chip tone="good" icon="fa-circle">Online</Chip>
                      : <span className="muted" title={new Date(d.lastSeen).toLocaleString()}>{timeAgo(d.lastSeen)}</span>}
                  </td>
                  <td>
                    {d.place ? (
                      <>
                        <span>{d.place.label}</span>
                        <span className="muted small block">
                          {SOURCE[d.place.source]?.short}
                          {d.vpnHint && <> · <span title="The browser’s time zone doesn’t match this location">possible VPN</span></>}
                        </span>
                      </>
                    ) : <span className="muted">Not known yet</span>}
                  </td>
                  <td>
                    <span className="mono">{d.ip || '—'}</span>
                    <span className="muted small block">
                      {VIA[d.via] || d.via}{d.ipGeo?.isp && !d.ipGeo.approximate ? ` · ${d.ipGeo.isp}` : ''}
                    </span>
                  </td>
                  <td className="num">
                    {d.downloads.toLocaleString()}
                    {d.downloadsToday > 0 && <span className="muted small block">{d.downloadsToday} today</span>}
                    {d.active > 0 && <span className="muted small block">{d.active} running</span>}
                  </td>
                  <td className="num">{bytes(d.bytes)}</td>
                  <td className="num">{d.visits.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty icon="fa-users" title={devices.length ? 'No devices match' : 'No devices yet'}>
          {devices.length ? 'Try another search or filter.' : 'Every browser that signs in shows up here.'}
        </Empty>
      )}
    </section>
  )
}
