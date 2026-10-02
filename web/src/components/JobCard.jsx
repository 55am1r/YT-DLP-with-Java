import { useEffect, useState } from 'react'
import { fileUrl, pauseJob, resumeJob, cancelJob } from '../api'
import { fmtSize, fmtSpeed, fmtElapsed, fmtCountdown, fmtKind } from '../utils'

const LABELS = {
  QUEUED: 'Queued',
  CHECKING_UPDATES: 'Checking yt-dlp',
  ANALYZING: 'Preparing',
  DOWNLOADING: 'Downloading',
  PAUSED: 'Paused',
  PROCESSING: 'Finalizing',
  COMPRESSING: 'Compressing',
  PACKAGING: 'Packaging',
  COMPLETED: 'Done',
  CANCELED: 'Canceled',
  FAILED: 'Failed',
}

const ACTIVE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'DOWNLOADING', 'PAUSED', 'PROCESSING',
  'COMPRESSING', 'PACKAGING'])
const PREFETCH_LIMIT = 200 * 1024 * 1024

function clamp(value) {
  return Math.max(0, Math.min(100, Number(value) || 0))
}

function stagesFor(job, finalizingProgress) {
  const isAudio = job.request?.kind?.toLowerCase() === 'audio'
  return isAudio
    ? [
        { key: 'PRIMARY', progress: clamp(job.primaryProgress) },
        { key: 'SECONDARY', progress: clamp(job.secondaryProgress) },
        { key: 'FINALIZING', progress: finalizingProgress },
      ]
    : [
        { key: 'PRIMARY', progress: clamp(job.primaryProgress) },
        { key: 'SECONDARY', progress: clamp(job.secondaryProgress) },
        { key: 'FINALIZING', progress: finalizingProgress },
      ]
}

function stageLabel(job, key, state) {
  const isAudio = job.request?.kind?.toLowerCase() === 'audio'
  const compressing = !isAudio && job.request?.codec && job.request.codec !== 'none'
  const labels = isAudio
    ? {
        PRIMARY: ['Audio queued', 'Downloading audio', 'Audio downloaded'],
        SECONDARY: ['Audio extraction queued', 'Extracting audio', 'Audio extracted'],
        FINALIZING: ['File finalization queued', 'Finalizing file', 'File finalized'],
      }
    : {
        PRIMARY: ['Video queued', 'Downloading video', 'Video downloaded'],
        SECONDARY: ['Audio queued', 'Downloading audio', 'Audio downloaded'],
        FINALIZING: [compressing ? 'Compression queued' : 'Finalization queued', compressing ? 'Compressing video' : 'Finalizing media', compressing ? 'Video compressed' : 'Media finalized'],
      }
  return labels[key][state === 'done' ? 2 : state === 'active' ? 1 : 0]
}

function stepState(job, step, done, bad) {
  if (done || step.progress >= 100) return 'done'
  if (!bad && job.currentStep === step.key) return 'active'
  return 'waiting'
}

/** Seconds left on the auto-save countdown, ticking on its own. */
function Secs({ to }) {
  const [, tick] = useState(0)
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 500)
    return () => clearInterval(t)
  }, [])
  return <b>{Math.max(0, Math.ceil((to - Date.now()) / 1000))}s</b>
}

/** Where a finished file is going, is on its way, or ended up. */
function SaveNote({ s, folder }) {
  if (!s) return null
  if (s.phase === 'wait') {
    return (
      <div className="job-note autosave-note">
        <i className="fa-regular fa-clock" /> Saving to your Downloads folder in <Secs to={s.deadline} />
        {folder && !folder.granted && <> — press <b>Save file</b> to use <b>{folder.name}</b></>}
      </div>
    )
  }
  if (s.phase === 'saving') {
    return (
      <div className="job-note autosave-note">
        <i className="fa-solid fa-circle-notch fa-spin" /> Saving to <b>{s.where || 'your Downloads folder'}</b>
        {s.pct > 0 && <> · {Math.round(s.pct * 100)}%</>}
      </div>
    )
  }
  if (s.phase === 'saved') {
    return (
      <div className="job-note autosave-note ok">
        <i className="fa-solid fa-check" />{' '}
        {s.fallbackFrom
          ? <>Couldn’t use <b>{s.fallbackFrom}</b> — saved to your Downloads folder instead</>
          : <>Saved to <b>{s.where || 'your Downloads folder'}</b></>}
      </div>
    )
  }
  return (
    <div className="job-note job-error-note">
      <i className="fa-solid fa-triangle-exclamation" /> Couldn’t save automatically — press Save file ({s.error})
    </div>
  )
}

export default function JobCard({ job, onExpired, onRetry, autosave }) {
  const [blobUrl, setBlobUrl] = useState(null)
  const [left, setLeft] = useState(null)
  const [expanded, setExpanded] = useState(false)

  const done = job.status === 'COMPLETED'
  const paused = job.status === 'PAUSED'
  const failed = job.status === 'FAILED'
  const bad = failed || job.status === 'CANCELED'
  const currentStep = job.currentStep || (['PROCESSING', 'COMPRESSING', 'PACKAGING'].includes(job.status) ? 'FINALIZING' : 'PRIMARY')
  const serverFinalizingProgress = clamp(job.finalizingProgress)
  const shouldEstimateFinalizing = !done && !bad && currentStep === 'FINALIZING' && ['PROCESSING', 'PACKAGING'].includes(job.status)
  const [finalizingEstimate, setFinalizingEstimate] = useState(serverFinalizingProgress)

  // yt-dlp reports real post-processing milestones but not byte progress for every
  // merge or metadata write. Keep that brief gap visibly alive, never crossing 95%
  // until the server confirms completion or ffmpeg provides a measured percentage.
  useEffect(() => {
    if (done) {
      setFinalizingEstimate(100)
      return
    }
    if (!shouldEstimateFinalizing) {
      setFinalizingEstimate(serverFinalizingProgress)
      return
    }
    setFinalizingEstimate((value) => Math.max(value, serverFinalizingProgress))
    const timer = setInterval(() => {
      setFinalizingEstimate((value) => Math.min(95, Math.max(value, serverFinalizingProgress) + 1))
    }, 900)
    return () => clearInterval(timer)
  }, [done, shouldEstimateFinalizing, serverFinalizingProgress])

  const finalizingProgress = shouldEstimateFinalizing
    ? Math.max(serverFinalizingProgress, finalizingEstimate)
    : serverFinalizingProgress
  const steps = stagesFor(job, finalizingProgress)
  const stageOverall = Math.round(steps.reduce((sum, step) => sum + step.progress, 0) / steps.length)
  const overall = done ? 100 : job.playlistCount > 1 ? clamp(job.progress) : stageOverall
  const isWorkingWithoutMeasure = !bad && !done && currentStep === 'FINALIZING' && finalizingProgress < 100

  const dl = job.downloadedBytes
  const total = job.totalBytes
  const sizeLine = job.status === 'DOWNLOADING' && dl > 0
    ? (total > 0
        ? <><b>{fmtSize(dl)}</b> of <b>{fmtSize(total)}</b></>
        : <><b>{fmtSize(dl)}</b> downloaded</>)
    : null

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
    <article className={`job glass ${bad ? 'job-failed' : ''} ${done ? 'job-done' : ''} ${expanded ? 'job-expanded' : ''}`}>
      <div className="job-summary">
        <div
          className={`overall-ring ${done ? 'done' : ''} ${bad ? 'bad' : ''} ${isWorkingWithoutMeasure ? 'working' : ''}`}
          style={{ '--job-progress': `${overall}%` }}
          aria-label={`Overall progress: ${overall}%`}
        >
          <span>{overall}%</span>
        </div>

        <div className="job-summary-main">
          <div className="job-head">
            <div className="job-title" title={job.title || ''}>{job.title || 'Preparing download'}</div>
            <div className={`status ${done ? 'ok' : bad ? 'bad' : ''}`}>{LABELS[job.status] || job.status}</div>
          </div>

          <div className="job-metrics">
            <span className="job-transfer">{sizeLine || job.phase}</span>
            <span className="job-rate">
              {job.status === 'DOWNLOADING' && job.speedBps > 0 && <><i className="fa-solid fa-gauge-high" /> {fmtSpeed(job.speedBps)}</>}
              {job.status === 'DOWNLOADING' && job.speedBps > 0 && job.eta && <span className="metric-divider" />}
              {job.status === 'DOWNLOADING' && job.eta && <><i className="fa-regular fa-clock" /> ETA {job.eta}</>}
              {done && fmtKind(job) && <>{fmtKind(job)}{job.fileSize > 0 && <span className="metric-divider" />}{job.fileSize > 0 && fmtSize(job.fileSize)}</>}
              {done && job.elapsedMs > 0 && <><span className="metric-divider" />{fmtElapsed(job.elapsedMs)}</>}
            </span>
          </div>

          {failed && <div className="job-note job-error-note">{job.error || 'Something went wrong'}</div>}
          {done && <SaveNote s={autosave.state[job.id]} folder={autosave.folder} />}
          {done && !['saving', 'saved'].includes(autosave.state[job.id]?.phase) && left != null && left > 0 && (
            <div className="job-note expiry">Download in <b>{fmtCountdown(left)}</b>, then the file is removed</div>
          )}
        </div>

        <div className="job-toolbar">
          {/* Every state gets the toggle. It used to be hidden once a job failed or was
              canceled, so a card left expanded when it died could never be collapsed. */}
          <button
            className="btn btn-sm job-details-toggle"
            type="button"
            onClick={() => setExpanded((value) => !value)}
            aria-expanded={expanded}
          >
            <i className={`fa-solid ${expanded ? 'fa-chevron-up' : 'fa-layer-group'}`} />
            {expanded ? 'Show less' : 'Check more info'}
          </button>
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
                title={autosave.folder ? `Save to ${autosave.folder.name}` : undefined}
                onClick={(e) => {
                  if (autosave.folder) { // the browser's own download can't target a chosen folder
                    e.preventDefault()
                    autosave.save(job)
                  } else {
                    autosave.saved(job) // native download carries on; just record it
                  }
                }}
              >
                <i className="fa-solid fa-download" /> Save file{blobUrl ? ' (ready)' : ''}
              </a>
            )}
          </div>
        </div>
      </div>

      <div className="job-details" aria-hidden={!expanded}>
        <div className="job-details-inner">
          <div className="download-detail">
            <div className={`steps-timeline ${done ? 'complete' : ''}`} style={{ '--timeline-progress': `${overall}%` }} aria-label="Download stages">
              {steps.map((step) => {
                const state = stepState({ ...job, currentStep }, step, done, bad)
                const label = stageLabel(job, step.key, state)
                return (
                  <div className={`timeline-step ${state}`} key={step.key}>
                    <div className="step-marker" aria-label={label}>
                      {state === 'done' && <i className="fa-solid fa-check" />}
                    </div>
                  </div>
                )
              })}
            </div>

            <ol className="download-stage-list">
            {steps.map((step) => {
              const state = stepState({ ...job, currentStep }, step, done, bad)
              const indeterminate = state === 'active' && step.progress === 0
              const label = stageLabel(job, step.key, state)
              return (
                <li className={`download-stage ${state}`} key={step.key}>
                  <div className="stage-label"><span>{label}</span><b>{state === 'done' ? 'Complete' : state === 'active' && step.progress > 0 ? `${step.progress}%` : state === 'active' ? 'Working' : 'Waiting'}</b></div>
                  <div className={`step-progress ${indeterminate ? 'indeterminate' : ''}`}>
                    <div className="step-progress-fill" style={{ width: `${step.progress}%` }} />
                  </div>
                </li>
              )
            })}
            </ol>
            <p className="download-detail-note">Progress is based on live system activity.</p>
          </div>
        </div>
      </div>
    </article>
  )
}
