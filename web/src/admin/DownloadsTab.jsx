import { useEffect, useState } from 'react'
import { downloadsCsvUrl, getDownloads } from './adminApi'
import { DownloadRow, Empty } from './AdminBits'
import Pager from './Pager'
import { usePager } from './paging'

const STATUSES = [
  { v: '', label: 'Any result' },
  { v: 'COMPLETED', label: 'Completed' },
  { v: 'FAILED', label: 'Failed' },
  { v: 'CANCELED', label: 'Canceled' },
  { v: 'ACTIVE', label: 'In progress' },
]
const KINDS = [{ v: '', label: 'Audio & video' }, { v: 'audio', label: 'Audio' }, { v: 'video', label: 'Video' }]
const PERIODS = [{ v: 0, label: 'All time' }, { v: 1, label: '24 hours' }, { v: 7, label: '7 days' }, { v: 30, label: '30 days' }]

/** The complete download log, filterable and paged, with a CSV export of whatever is filtered. */
export default function DownloadsTab({ devices, onSelectDevice }) {
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [kind, setKind] = useState('')
  const [days, setDays] = useState(0)
  const [device, setDevice] = useState('')
  const [page, setPage] = useState({ total: 0, items: [] })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 300)
    return () => clearTimeout(t)
  }, [text])

  const filterKey = JSON.stringify({ q, status, kind, days, device })
  const pager = usePager(page.total, { id: 'downloads', resetKey: filterKey })

  useEffect(() => {
    let alive = true
    setLoading(true)
    getDownloads({ q, status, kind, days, device }, pager.offset, pager.size)
      .then((p) => { if (alive) { setPage(p); setError(null) } })
      .catch((e) => { if (alive) setError(e.message || 'Could not load downloads') })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
    // filterKey stands for the five filters it is built from
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey, pager.offset, pager.size, refresh])

  const named = [...devices].sort((a, b) => a.name.localeCompare(b.name))

  return (
    <section className="panel glass">
      <div className="toolbar">
        <input
          className="input input-sm" type="search" placeholder="Search title, link or file name"
          value={text} onChange={(e) => setText(e.target.value)} aria-label="Search downloads"
        />
        <select className="select" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Period">
          {PERIODS.map((p) => <option key={p.v} value={p.v}>{p.label}</option>)}
        </select>
        <select className="select" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Result">
          {STATUSES.map((s) => <option key={s.v} value={s.v}>{s.label}</option>)}
        </select>
        <select className="select" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Type">
          {KINDS.map((k) => <option key={k.v} value={k.v}>{k.label}</option>)}
        </select>
        <select className="select" value={device} onChange={(e) => setDevice(e.target.value)} aria-label="Device">
          <option value="">Every device</option>
          {named.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
        <span className="toolbar-end">
          <button type="button" className="btn btn-sm" onClick={() => setRefresh((n) => n + 1)} title="Refresh">
            <i className="fa-solid fa-rotate-right" />
          </button>
          <a className="btn btn-sm" href={downloadsCsvUrl({ q, status, kind, days, device })} download>
            <i className="fa-solid fa-file-csv" /> Export CSV
          </a>
        </span>
      </div>

      {error && <div className="error">{error}</div>}
      <p className="muted small">
        {loading ? 'Loading…' : `${page.total.toLocaleString()} ${page.total === 1 ? 'download' : 'downloads'}`}
      </p>

      <div className={loading ? 'refetching' : ''} ref={pager.topRef}>
        {page.items.length ? (
          <ul className="dl-list">
            {page.items.map((r) => <DownloadRow key={r.jobId} r={r} onSelectDevice={onSelectDevice} />)}
          </ul>
        ) : !loading && (
          <Empty icon="fa-download" title="No downloads match">Try a longer period or clear the search.</Empty>
        )}
      </div>

      <Pager pager={pager} noun={page.total === 1 ? 'download' : 'downloads'} />
    </section>
  )
}
