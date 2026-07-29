import { useEffect, useState } from 'react'
import { fileUrl, pauseJob, resumeJob, cancelJob } from '../api'
import { fmtSize, fmtElapsed, fmtCountdown, fmtKind } from '../utils'

const LABELS = {
  QUEUED: 'Queued',
  CHECKING_UPDATES: 'Checking yt-dlp…',
  ANALYZING: 'Analyzing…',
  DOWNLOADING: 'Downloading',
  PAUSED: 'Paused',
  PROCESSING: 'Processing',
  COMPRESSING: 'Compressing',
  PACKAGING: 'Packaging',
  COMPLETED: 'Done',
  CANCELED: 'Canceled',
  FAILED: 'Failed',
}

// COMPRESSING reports a real percentage from ffmpeg, so it gets a real bar.
const INDETERMINATE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'PROCESSING', 'PACKAGING'])
const ACTIVE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'DOWNLOADING', 'PAUSED', 'PROCESSING',
  'COMPRESSING', 'PACKAGING'])

// Files above this are streamed straight from the server instead of being pre-fetched,
// so a multi-GB 4K download can't blow up the tab's memory.
const PREFETCH_LIMIT = 200 * 1024 * 1024

export default function JobCard({ job, onExpired, onRetry, onSaved }) {
  const [blobUrl, setBlobUrl] = useState(null)
  const [left, setLeft] = useState(null)

  const done = job.status === 'COMPLETED'
  const paused = job.status === 'PAUSED'
  const failed = job.status === 'FAILED'
  const bad = failed || job.status === 'CANCELED'
  const indet = INDETERMINATE.has(job.status) || (job.status === 'DOWNLOADING' && (job.progress || 0) === 0)
  const pct = Math.max(0, Math.min(100, job.progress || 0))

  // Pre-fetch the finished file so "Save file" is instant when the user comes back.
  useEffect(() => {
    if (!done || !job.fileSize || job.fileSize > PREFETCH_LIMIT) return
    let dead = false
    let url = null
    fetch(fileUrl(job.id))
      .then((r) => (r.ok ? r.blob() : null))
      .then((b) => {
        if (b && !dead) {
          url = URL.createObjectURL(b)
          setBlobUrl(url)
        }
      })
      .catch(() => {})
    return () => {
      dead = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [done, job.id, job.fileSize])

  // Countdown to the server deleting the file; drop the row when it's gone.
  useEffect(() => {
    if (!job.expiresAt) return
    const tick = () => {
      const ms = job.expiresAt - Date.now()
      setLeft(ms)
      if (ms <= 0 && onExpired) onExpired(job.id)
    }
    tick()
    const t = setInterval(tick, 1000)
    return () => clearInterval(t)
  }, [job.expiresAt, job.id, onExpired])

  return (
    <div className={`job glass ${bad ? 'job-failed' : ''} ${done ? 'job-done' : ''}`}>
      <div className="job-head">
        <div className="job-title">{job.title || 'Preparing…'}</div>
        <div className={`status ${done ? 'ok' : bad ? 'bad' : ''}`}>{LABELS[job.status] || job.status}</div>
      </div>

      {!bad && (
        <div className={`progress ${indet ? 'indet' : ''} ${paused ? 'paused' : ''}`}>
          <div className="bar-fill" style={{ width: indet ? '40%' : `${pct}%` }} />
        </div>
      )}

      <div className="job-info">
        {job.status === 'DOWNLOADING' && job.speed && <span><i className="fa-solid fa-gauge-high" /> <b>{job.speed}</b></span>}
        {job.status === 'DOWNLOADING' && job.eta && <span><i className="fa-regular fa-clock" /> ETA <b>{job.eta}</b></span>}
        {done && fmtKind(job) && <span><b>{fmtKind(job)}</b></span>}
        {done && job.fileSize > 0 && <span>{fmtSize(job.fileSize)}</span>}
        {done && job.elapsedMs > 0 && <span>took <b>{fmtElapsed(job.elapsedMs)}</b></span>}
      </div>

      {done && left != null && left > 0 && (
        <div className="job-info">
          <span className="expiry">Download in <b>{fmtCountdown(left)}</b>, else the file will be removed</span>
        </div>
      )}

      <div className="job-foot">
        <span className="muted small">{failed ? job.error || 'Something went wrong' : job.phase}</span>
        <div className="job-actions">
          {job.status === 'DOWNLOADING' && (
            <button className="btn btn-sm" onClick={() => pauseJob(job.id)}>
              <i className="fa-solid fa-pause" /> Pause
            </button>
          )}
          {paused && (
            <button className="btn btn-sm" onClick={() => resumeJob(job.id)}>
              <i className="fa-solid fa-play" /> Resume
            </button>
          )}
          {ACTIVE.has(job.status) && (
            <button className="btn btn-sm btn-ghost danger" onClick={() => cancelJob(job.id)}>
              <i className="fa-solid fa-xmark" /> Cancel
            </button>
          )}
          {/* Re-run with the exact same settings — for a failed job, or one whose file
              the server lost on a restart. */}
          {bad && job.request && onRetry && (
            <button className="btn btn-sm" onClick={() => onRetry(job)}>
              <i className="fa-solid fa-rotate-right" /> Retry
            </button>
          )}
          {done && (
            <a
              className="btn btn-primary btn-sm"
              href={blobUrl || fileUrl(job.id)}
              download={job.fileName || true}
              onClick={() => onSaved && onSaved(job.id)}
            >
              <i className="fa-solid fa-download" /> Save file{blobUrl ? ' (ready)' : ''}
            </a>
          )}
        </div>
      </div>
    </div>
  )
}
