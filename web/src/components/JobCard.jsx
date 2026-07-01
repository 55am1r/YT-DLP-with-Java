import { fileUrl } from '../api'

const LABELS = {
  QUEUED: 'Queued',
  CHECKING_UPDATES: 'Checking yt-dlp…',
  ANALYZING: 'Analyzing…',
  DOWNLOADING: 'Downloading',
  PROCESSING: 'Processing',
  PACKAGING: 'Packaging',
  COMPLETED: 'Done',
  FAILED: 'Failed',
}

const INDETERMINATE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'PROCESSING', 'PACKAGING'])

export default function JobCard({ job }) {
  const done = job.status === 'COMPLETED'
  const failed = job.status === 'FAILED'
  const indet = INDETERMINATE.has(job.status)
  const pct = Math.max(0, Math.min(100, job.progress || 0))

  return (
    <div className={`job ${failed ? 'job-failed' : ''} ${done ? 'job-done' : ''}`}>
      <div className="job-head">
        <div className="job-title">{job.title || 'Preparing…'}</div>
        <div className={`status ${done ? 'ok' : failed ? 'bad' : ''}`}>{LABELS[job.status] || job.status}</div>
      </div>

      {!failed && (
        <div className={`progress ${indet ? 'indet' : ''}`}>
          <div className="bar-fill" style={{ width: indet ? '40%' : `${pct}%` }} />
        </div>
      )}

      <div className="job-foot">
        <span className="muted small">{failed ? job.error || 'Something went wrong' : job.phase}</span>
        {done && (
          <a className="btn btn-primary btn-sm" href={fileUrl(job.id)} download>
            ⬇ Save file
          </a>
        )}
      </div>
    </div>
  )
}
