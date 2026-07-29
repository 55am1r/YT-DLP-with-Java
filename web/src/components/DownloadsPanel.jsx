import JobCard from './JobCard'

/**
 * The downloads for ONE link. Always shown — even with nothing in it — so the section
 * and its (disabled) Clear button stay put and a placeholder explains the empty state.
 * Clearing here only wipes this link's files on the server; other tabs keep theirs.
 */
export default function DownloadsPanel({ jobs, onClear, onExpired, onRetry, onSaved, clearing }) {
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
          <JobCard key={j.id} job={j} onExpired={onExpired} onRetry={onRetry} onSaved={onSaved} />
        ))
      )}
    </section>
  )
}
