import JobCard from './JobCard'
import { GRACE_SECONDS } from '../autosave'

/**
 * The Auto-save switch, beside Clear. On: every finished download saves itself — into the
 * folder chosen on its own card, or into the Downloads folder after a short countdown.
 * Off: nothing saves by itself and the cards go back to a plain Save file button.
 */
function AutoSaveSwitch({ a }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={a.on}
      className={`switch${a.on ? ' on' : ''}`}
      onClick={() => a.setOn(!a.on)}
      title={a.on
        ? `Auto-save is on. Each finished download is written straight into its folder.${a.fallback ? ' No folder is set, so files are handed to your browser instead.' : ''}`
        : `Auto-save is off. Press Save file on each download. Turning it on asks once for a folder, so nothing prompts you later.`}
    >
      <span className="switch-track"><span className="switch-knob" /></span>
      <span>Auto-save</span>
    </button>
  )
}

/**
 * The downloads for ONE link. Always shown — even with nothing in it — so the section
 * and its (disabled) Clear button stay put and a placeholder explains the empty state.
 * Clearing here only wipes this link's files on the server; other tabs keep theirs.
 */
export default function DownloadsPanel({ jobs, onClear, onExpired, onRetry, autosave, clearing }) {
  const clearable = jobs.some((j) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(j.status))

  return (
    <section className="jobs">
      <div className="dl-head">
        <h2 className="section-title">Downloads</h2>
        <div className="dl-head-actions">
          <AutoSaveSwitch a={autosave} />
          <button
            className="btn btn-sm"
            onClick={onClear}
            disabled={!clearable || clearing}
            title="Remove these files from the server"
          >
            {clearing
              ? <><i className="fa-solid fa-circle-notch fa-spin" /> Clearing…</>
              : <><i className="fa-solid fa-trash-can" /> Clear</>}
          </button>
        </div>
      </div>

      {/* Without a folder nothing can be written silently — the browser's own downloader
          takes over, and it may ask where to put each file. Say so once, here, rather than
          on every card. */}
      {autosave.on && !autosave.canPick && (
        <p className="dl-hint">
          Finished files go to your browser {GRACE_SECONDS}s after they finish, and it may ask where to save each one.
          {window.isSecureContext ? ' Saving without a prompt needs Chrome or Edge.' : ' Saving without a prompt needs the secure (https) link.'}
        </p>
      )}
      {autosave.on && autosave.canPick && autosave.fallback && (
        <p className="dl-hint">
          <i className="fa-solid fa-triangle-exclamation" /> No folder chosen, so finished files are handed to your
          browser — which may ask where to save each one. Choose a folder on any download below to save silently.
        </p>
      )}

      {jobs.length === 0 ? (
        <div className="job glass job-empty">
          <div className="job-empty-icon"><i className="fa-solid fa-cloud-arrow-down" /></div>
          <div>
            <div className="job-title">No downloads yet</div>
            <span className="muted small">Choose your options and hit download — files will appear here.</span>
          </div>
        </div>
      ) : (
        jobs.map((j) => (
          <JobCard key={j.id} job={j} onExpired={onExpired} onRetry={onRetry} autosave={autosave} />
        ))
      )}
    </section>
  )
}
