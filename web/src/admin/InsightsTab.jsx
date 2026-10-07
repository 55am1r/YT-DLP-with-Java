import { useEffect, useState } from 'react'
import { getInsights } from './adminApi'
import { BarList, ColumnChart, SplitBar } from './charts'
import { DeviceLink, Empty } from './AdminBits'
import { bytes, fmtDay, safeHref, statusMeta, ytThumb } from './format'

const PERIODS = [7, 30, 90]
const QUALITY_ORDER = ['2160p', '1440p', '1080p', '720p', '480p or less']

/** What the team downloads, when, and from where — for the chosen period. */
export default function InsightsTab({ onSelectDevice }) {
  const [days, setDays] = useState(30)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [tables, setTables] = useState(false)

  useEffect(() => {
    let alive = true
    getInsights(days)
      .then((d) => { if (alive) { setData(d); setError(null) } })
      .catch((e) => { if (alive) setError(e.message || 'Could not load insights') })
    return () => { alive = false }
  }, [days])

  const total = data ? data.perDay.reduce((s, d) => s + d.downloads, 0) : 0

  return (
    <>
      <div className="filter-row">
        <div className="seg seg-sm glass" role="group" aria-label="Period">
          {PERIODS.map((p) => (
            <button key={p} type="button" className={`seg-btn ${days === p ? 'active' : ''}`}
              aria-pressed={days === p} onClick={() => setDays(p)}>
              {p} days
            </button>
          ))}
        </div>
        <label className="check">
          <input type="checkbox" checked={tables} onChange={(e) => setTables(e.target.checked)} />
          <span>Show charts as tables</span>
        </label>
        {data && <span className="muted small">{total.toLocaleString()} downloads in the last {days} days</span>}
      </div>

      {error && <div className="error">{error}</div>}
      {!data ? <p className="muted">Loading…</p> : total === 0 ? (
        <section className="panel glass"><Empty icon="fa-chart-column" title="No downloads in this period" /></section>
      ) : (
        <div className={data.days !== days ? 'refetching' : ''}>
          <section className="panel glass">
            <div className="panel-head"><h2>Downloads per day</h2></div>
            {tables ? (
              <SimpleTable
                head={['Day', 'Downloads', 'Devices', 'Delivered']}
                rows={data.perDay.map((d) => [fmtDay(d.day), d.downloads, d.devices, bytes(d.bytes)])}
              />
            ) : (
              <ColumnChart
                ariaLabel="Downloads per day"
                data={data.perDay.map((d) => ({
                  key: d.day, label: fmtDay(d.day), value: d.downloads,
                  detail: `${d.devices} ${d.devices === 1 ? 'device' : 'devices'} · ${bytes(d.bytes)}`,
                }))}
              />
            )}
          </section>

          <div className="admin-cols">
            <section className="panel glass">
              <div className="panel-head">
                <h2>Busiest hours</h2>
                <span className="muted small">server time</span>
              </div>
              {tables ? (
                <SimpleTable head={['Hour', 'Downloads started']}
                  rows={data.perHour.map((n, h) => [`${String(h).padStart(2, '0')}:00`, n])} />
              ) : (
                <ColumnChart
                  ariaLabel="Downloads by hour of day" height={150}
                  data={data.perHour.map((n, h) => ({ key: String(h), label: String(h).padStart(2, '0'), value: n }))}
                />
              )}
            </section>
            <section className="panel glass">
              <div className="panel-head"><h2>Audio or video</h2></div>
              <SplitBar parts={[
                { key: 'audio', label: 'Audio', value: data.kinds.audio || 0, color: 'var(--viz-1)' },
                { key: 'video', label: 'Video', value: data.kinds.video || 0, color: 'var(--viz-2)' },
              ]} />
              <h3 className="sub-head">Video quality</h3>
              <BarList
                empty="No videos in this period"
                items={QUALITY_ORDER.filter((q) => data.qualities[q]).map((q) => ({ key: q, label: q, value: data.qualities[q] }))}
              />
            </section>
          </div>

          <section className="panel glass">
            <div className="panel-head"><h2>Most downloaded</h2></div>
            <ol className="top-videos">
              {data.topVideos.map((v, i) => {
                const href = safeHref(v.extra)
                const thumb = /^[A-Za-z0-9_-]{11}$/.test(v.key) ? ytThumb(v.key) : null
                return (
                  <li key={v.key}>
                    <span className="rank">{i + 1}</span>
                    <div className="dl-thumb" aria-hidden="true">
                      {thumb ? <img src={thumb} alt="" loading="lazy" referrerPolicy="no-referrer" /> : <i className="fa-solid fa-film" />}
                    </div>
                    <div className="dl-main">
                      <div className="dl-title">
                        {href ? <a href={href} target="_blank" rel="noopener noreferrer">{v.label}</a> : <span>{v.label}</span>}
                      </div>
                      <div className="dl-meta muted">
                        {v.count} {v.count === 1 ? 'download' : 'downloads'} · by {v.devices} {v.devices === 1 ? 'device' : 'devices'}
                      </div>
                    </div>
                  </li>
                )
              })}
            </ol>
          </section>

          <div className="admin-cols three">
            <section className="panel glass">
              <div className="panel-head"><h2>Where from</h2></div>
              <BarList items={data.topPlaces.map((p) => ({
                key: p.key, label: p.label, value: p.count, sub: `${p.devices} ${p.devices === 1 ? 'device' : 'devices'}`,
              }))} />
            </section>
            <section className="panel glass">
              <div className="panel-head"><h2>Most active devices</h2></div>
              <BarList items={data.topDevices.map((p) => ({
                key: p.key, value: p.count,
                label: <DeviceLink id={p.key === '?' ? null : p.key} name={p.label} onSelect={onSelectDevice} />,
              }))} />
            </section>
            <section className="panel glass">
              <div className="panel-head"><h2>Formats & results</h2></div>
              <BarList items={Object.entries(data.formats).sort((a, b) => b[1] - a[1])
                .map(([f, n]) => ({ key: f, label: f, value: n }))} />
              <h3 className="sub-head">How they ended</h3>
              <BarList items={Object.entries(data.statuses).sort((a, b) => b[1] - a[1]).map(([s, n]) => {
                const m = statusMeta(s)
                return { key: s, label: m.label, icon: m.icon, value: n }
              })} />
            </section>
          </div>
        </div>
      )}
    </>
  )
}

function SimpleTable({ head, rows }) {
  return (
    <div className="table-wrap">
      <table className="admin-table compact">
        <thead><tr>{head.map((h, i) => <th key={h} className={i ? 'num' : ''}>{h}</th>)}</tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r[0]}>{r.map((c, i) => <td key={i} className={i ? 'num' : ''}>{typeof c === 'number' ? c.toLocaleString() : c}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
