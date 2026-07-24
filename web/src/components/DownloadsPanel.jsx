import JobCard from './JobCard'

/**
 * The downloads for ONE link. Clearing here only wipes this link's files on the
 * server — other tabs keep theirs.
 */
export default function DownloadsPanel({ jobs, onClear, onExpired, clearing }) {
  if (jobs.length === 0) return null
  const clearable = jobs.some((j) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(j.status))

  return (
    <section className="jobs">
      <div className="dl-head">
        <h2 className="section-title">Downloads</h2>
        <button className="btn btn-sm" onClick={onClear} disabled={!clearable || clearing} title="Remove these files from the server">
          {clearing ? <><i className="fa-solid fa-circle-notch fa-spin" /> Clearing…</> : <><i className="fa-solid fa-trash-can" /> Clear</>}
        </button>
      </div>
      {jobs.map((j) => (
        <JobCard key={j.id} job={j} onExpired={onExpired} />
      ))}
    </section>
  )
}
