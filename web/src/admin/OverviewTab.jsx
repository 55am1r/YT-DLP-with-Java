import { useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import { fmtSpeed } from '../utils'
import { Chip, DeviceLink, DownloadRow, Empty, Kpi, StatusChip } from './AdminBits'
import Globe from './Globe'
import { bytes, compact, EVENT, timeAgo } from './format'

export default function OverviewTab({ data, selectedId, onSelectDevice, onCancelJob, onShowTab }) {
  const k = data.kpis
  return (
    <>
      <div className="kpi-row">
        <Kpi label="Online now" value={k.onlineNow} sub={`${k.usersToday} today · ${k.users7d} this week`} />
        <Kpi label="Downloads today" value={compact(k.downloadsToday)}
          sub={`${compact(k.downloads7d)} this week · ${compact(k.downloadsTotal)} in all`} />
        <Kpi label="Delivered today" value={bytes(k.bytesToday)} sub={`${bytes(k.bytesTotal)} in all`} />
        <Kpi label="Success rate" value={k.successRate7d == null ? '—' : `${k.successRate7d}%`} sub="finished downloads, 7 days" />
        <Kpi label="Running now" value={k.activeJobs} sub={k.queuedJobs ? `${k.queuedJobs} waiting for a slot` : 'none waiting'} />
        <Kpi label="Devices" value={compact(k.devicesTotal)}
          sub={`${k.countries} ${k.countries === 1 ? 'country' : 'countries'}`} />
        {k.failedLogins24h > 0 && (
          <Kpi label="Wrong logins, 24 h" value={k.failedLogins24h} sub="see Security" tone="warning" />
        )}
      </div>

      <section className="panel glass map-panel">
        <div className="panel-head">
          <h2>Where the team is</h2>
          <button type="button" className="link-btn" onClick={() => onShowTab('users')}>See them as a list</button>
        </div>
        <Globe devices={data.devices} selectedId={selectedId} onSelect={onSelectDevice} />
      </section>

      <div className="admin-cols">
        <section className="panel glass">
          <div className="panel-head">
            <h2>Downloading now</h2>
            <span className="muted small">{data.live.length ? `${data.live.length} running` : ''}</span>
          </div>
          <LiveJobs jobs={data.live} onSelectDevice={onSelectDevice} onCancel={onCancelJob} />
        </section>
        <section className="panel glass">
          <div className="panel-head"><h2>Recent activity</h2></div>
          <ActivityFeed events={data.activity.slice(0, 14)} onSelectDevice={onSelectDevice} />
        </section>
      </div>

      <section className="panel glass">
        <div className="panel-head">
          <h2>Latest downloads</h2>
          <button type="button" className="link-btn" onClick={() => onShowTab('downloads')}>All downloads</button>
        </div>
        {data.recent.length
          ? <ul className="dl-list">{data.recent.slice(0, 8).map((r) => <DownloadRow key={r.jobId} r={r} onSelectDevice={onSelectDevice} />)}</ul>
          : <Empty icon="fa-download" title="No downloads yet">They show up here as soon as anyone starts one.</Empty>}
      </section>
    </>
  )
}

function LiveJobs({ jobs, onSelectDevice, onCancel }) {
  const [confirm, setConfirm] = useState(null)
  if (!jobs.length) return <Empty icon="fa-mug-hot" title="Nothing is downloading">It’s safe to restart the server.</Empty>
  return (
    <>
      <ul className="live-list">
        {jobs.map((j) => (
          <li key={j.id}>
            <div className="live-top">
              <span className="live-title">{j.title || 'Preparing…'}</span>
              <StatusChip status={j.status === 'QUEUED' || j.status === 'PAUSED' ? j.status : 'ACTIVE'} />
            </div>
            <div className="meter" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={j.progress}>
              <span style={{ width: `${j.progress}%` }} />
            </div>
            <div className="live-meta">
              <DeviceLink id={j.deviceId} name={j.deviceName} onSelect={onSelectDevice} />
              <span className="muted">{j.phase}</span>
              {j.playlistCount ? <span className="muted">item {j.playlistIndex || 1} of {j.playlistCount}</span> : null}
              {j.speedBps ? <span className="muted">{fmtSpeed(j.speedBps)}{j.eta ? ` · ${j.eta} left` : ''}</span> : null}
              <button type="button" className="link-btn danger" onClick={() => setConfirm(j)}>Cancel</button>
            </div>
          </li>
        ))}
      </ul>
      {confirm && (
        <ConfirmDialog
          title="Cancel this download?"
          message={`“${confirm.title || 'This download'}” stops for ${confirm.deviceName || 'its owner'}, and the partial file is deleted.`}
          detail="They can start it again later."
          cancelLabel="Keep it running" confirmLabel="Cancel download"
          headerIcon="fa-ban" confirmIcon="fa-ban"
          onCancel={() => setConfirm(null)}
          onConfirm={() => { onCancel(confirm.id); setConfirm(null) }}
        />
      )}
    </>
  )
}

export function ActivityFeed({ events, onSelectDevice }) {
  if (!events.length) return <Empty icon="fa-wave-square" title="No activity yet" />
  return (
    <ul className="feed">
      {events.map((e, i) => {
        const m = EVENT[e.type] || { label: e.type, icon: 'fa-circle', tone: 'muted' }
        return (
          <li key={`${e.at}-${i}`}>
            <Chip tone={m.tone} icon={m.icon}>{m.label}</Chip>
            <div className="feed-body">
              <span className="feed-text">
                {e.deviceId && e.deviceName !== 'Unknown device'
                  ? <DeviceLink id={e.deviceId} name={e.deviceName} onSelect={onSelectDevice} />
                  : <span className="muted">{e.ip || 'Unknown'}</span>}
                {e.title ? <> · “{e.title}”</> : null}
                {e.detail ? <> · {e.detail}</> : null}
              </span>
              <span className="muted small">{timeAgo(e.at)}{e.place ? ` · ${e.place}` : ''}</span>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
