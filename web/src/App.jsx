import { useEffect, useRef, useState } from 'react'
import Header from './components/Header'
import UrlBar from './components/UrlBar'
import MediaPanel from './components/MediaPanel'
import JobCard from './components/JobCard'
import Login from './components/Login'
import { analyze, startJob, checkAuth, logout as apiLogout } from './api'

export default function App() {
  const [authed, setAuthed] = useState(null) // null = still checking
  const [analysis, setAnalysis] = useState(null)
  const [analyzing, setAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [jobs, setJobs] = useState([])
  const sources = useRef(new Map())

  useEffect(() => {
    checkAuth().then(setAuthed).catch(() => setAuthed(false))
  }, [])

  // Close all live streams when the app unmounts.
  useEffect(() => () => sources.current.forEach((es) => es.close()), [])

  // If the session expired, drop back to the login screen.
  function handleAuthError(e) {
    if (e && e.status === 401) {
      setAuthed(false)
      return true
    }
    return false
  }

  async function onAnalyze(url) {
    setError(null)
    setAnalyzing(true)
    setAnalysis(null)
    try {
      setAnalysis(await analyze(url))
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not analyze that URL')
    } finally {
      setAnalyzing(false)
    }
  }

  // Poll a job's progress. SSE would be nicer, but it gets buffered by the Cloudflare
  // tunnel the team uses (progress looks frozen, then jumps to done). Polling is plain
  // GETs that stream reliably through any proxy — tunnel, Netlify redirect, or direct.
  function track(id) {
    if (sources.current.has(id)) return
    let timer = null
    const stop = () => {
      if (timer) clearInterval(timer)
      sources.current.delete(id)
    }
    const tick = async () => {
      try {
        const res = await fetch(`/api/jobs/${id}`)
        if (res.status === 401) {
          stop()
          setAuthed(false)
          return
        }
        if (!res.ok) return
        const job = await res.json()
        setJobs((prev) => prev.map((j) => (j.id === job.id ? { ...j, ...job } : j)))
        if (job.status === 'COMPLETED' || job.status === 'FAILED' || job.status === 'CANCELED') {
          stop()
        }
      } catch {
        // transient hiccup — keep polling
      }
    }
    timer = setInterval(tick, 800)
    sources.current.set(id, { close: stop })
    tick()
  }

  async function onStart(request) {
    setError(null)
    try {
      const job = await startJob(request)
      setJobs((prev) => [job, ...prev.filter((j) => j.id !== job.id)])
      track(job.id)
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not start the download')
    }
  }

  async function onLogout() {
    await apiLogout()
    setAnalysis(null)
    setJobs([])
    setAuthed(false)
  }

  if (authed === null) {
    return (
      <div className="app">
        <div className="container loading muted">Loading…</div>
      </div>
    )
  }

  if (!authed) {
    return <Login onSuccess={() => setAuthed(true)} />
  }

  return (
    <div className="app">
      <div className="container">
        <Header />
        <UrlBar onAnalyze={onAnalyze} analyzing={analyzing} />
        {error && <div className="error">{error}</div>}
        {analysis && <MediaPanel analysis={analysis} onStart={onStart} />}
        {jobs.length > 0 && (
          <section className="jobs">
            <h2 className="section-title">Downloads</h2>
            {jobs.map((j) => (
              <JobCard key={j.id} job={j} />
            ))}
          </section>
        )}
      </div>
      <footer className="foot muted">
        by PredatorFX · for ChaitusMedia Team use ·{' '}
        <button className="linklike" onClick={onLogout}>Log out</button>
      </footer>
    </div>
  )
}
