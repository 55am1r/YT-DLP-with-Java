import JobCard from './JobCard'
import { GRACE_SECONDS } from '../autosave'

/**
 * The downloads for ONE link. Always shown — even with nothing in it — so the section
 * and its (disabled) Clear button stay put and a placeholder explains the empty state.
 * Clearing here only wipes this link's files on the server; other tabs keep theirs.
 */
/**
 * Where finished files from THIS tab go — every tab (one per link) has its own. Lives here,
 * not on each card, so it can be set before a download starts, mid-download, and never
 * gets in the way once one has finished.
 */
function SaveTarget({ a, pageId }) {
  const folder = a.folders[pageId]
  const { canPick } = a
  return (
    <div className="save-target glass">
      <div className="save-target-text">
        <i className="fa-regular fa-folder-open" />
        <span>
          {folder
            ? <>Saves to <b>{folder.name}</b>{!folder.granted && <span className="save-target-warn"> · needs access</span>}</>
            : <>Saves to your <b>Downloads</b> folder</>}
        </span>
      </div>
      <div className="save-target-actions">
        {folder && !folder.granted && <button className="btn btn-sm btn-primary" onClick={() => a.allow(pageId)}>Allow</button>}
        {canPick && <button className="btn btn-sm" onClick={() => a.pick(pageId)}>{folder ? 'Change' : 'Choose folder'}</button>}
        {folder && (
          <button className="btn btn-sm btn-ghost" onClick={() => a.clear(pageId)} aria-label="Go back to the Downloads folder" title="Go back to the Downloads folder">
            <i className="fa-solid fa-xmark" />
          </button>
        )}
      </div>
      <p className="save-target-hint">
        {folder
          ? 'Finished files from this tab save here by themselves.'
          : `Finished files save themselves after ${GRACE_SECONDS}s${canPick ? ' — or choose a folder for this tab to save the moment they’re ready.' : '.'}`}
        {!canPick && (window.isSecureContext ? ' Choosing a folder needs Chrome or Edge.' : ' Choosing a folder needs the secure (https) link.')}
      </p>
    </div>
  )
}

export default function DownloadsPanel({ jobs, pageId, onClear, onExpired, onRetry, autosave, clearing }) {
  const clearable = jobs.some((j) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(j.status))

  return (
    <section className="jobs">
      <div className="dl-head">
        <h2 className="section-title">Downloads</h2>
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

      <SaveTarget a={autosave} pageId={pageId} />

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
          <JobCard key={j.id} job={j} onExpired={onExpired} onRetry={onRetry} autosave={autosave} folder={autosave.folders[pageId]} />
        ))
      )}
    </section>
  )
}
