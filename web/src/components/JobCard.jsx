import { fileUrl, pauseJob, resumeJob, cancelJob } from '../api'

const LABELS = {
  QUEUED: 'Queued',
  CHECKING_UPDATES: 'Checking yt-dlp…',
  ANALYZING: 'Analyzing…',
  DOWNLOADING: 'Downloading',
  PAUSED: 'Paused',
  PROCESSING: 'Processing',
  PACKAGING: 'Packaging',
  COMPLETED: 'Done',
  CANCELED: 'Canceled',
  FAILED: 'Failed',
}

const INDETERMINATE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'PROCESSING', 'PACKAGING'])
const ACTIVE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'DOWNLOADING', 'PAUSED', 'PROCESSING', 'PACKAGING'])

export default function JobCard({ job }) {
  const done = job.status === 'COMPLETED'
  const paused = job.status === 'PAUSED'
  const bad = job.status === 'FAILED' || job.status === 'CANCELED'
  const indet = INDETERMINATE.has(job.status) || (job.status === 'DOWNLOADING' && (job.progress || 0) === 0)
  const pct = Math.max(0, Math.min(100, job.progress || 0))
  const canPause = job.status === 'DOWNLOADING'
  const canCancel = ACTIVE.has(job.status)

  return (
    <div className={`job ${bad ? 'job-failed' : ''} ${done ? 'job-done' : ''}`}>
      <div className="job-head">
        <div className="job-title">{job.title || 'Preparing…'}</div>
        <div className={`status ${done ? 'ok' : bad ? 'bad' : ''}`}>{LABELS[job.status] || job.status}</div>
      </div>

      {!bad && (
        <div className={`progress ${indet ? 'indet' : ''} ${paused ? 'paused' : ''}`}>
          <div className="bar-fill" style={{ width: indet ? '40%' : `${pct}%` }} />
        </div>
      )}

      <div className="job-foot">
        <span className="muted small">
          {job.status === 'FAILED' ? job.error || 'Something went wrong' : job.phase}
        </span>
        <div className="job-actions">
          {canPause && (
            <button className="btn btn-ghost btn-sm" onClick={() => pauseJob(job.id)}>⏸ Pause</button>
          )}
          {paused && (
            <button className="btn btn-ghost btn-sm" onClick={() => resumeJob(job.id)}>▶ Resume</button>
          )}
          {canCancel && (
            <button className="btn btn-ghost btn-sm danger" onClick={() => cancelJob(job.id)}>✕ Cancel</button>
          )}
          {done && (
            <a className="btn btn-primary btn-sm" href={fileUrl(job.id)} download>⬇ Save file</a>
          )}
        </div>
      </div>
    </div>
  )
}
