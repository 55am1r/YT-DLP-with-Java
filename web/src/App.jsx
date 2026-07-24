import { useCallback, useEffect, useRef, useState } from 'react'
import Header from './components/Header'
import UrlBar from './components/UrlBar'
import PageTabs from './components/PageTabs'
import MediaPanel from './components/MediaPanel'
import PlaylistPanel from './components/PlaylistPanel'
import DownloadsPanel from './components/DownloadsPanel'
import ConfirmDialog from './components/ConfirmDialog'
import Login from './components/Login'
import { analyze, startJob, checkAuth, clearJobs, getCodecs, logout as apiLogout } from './api'

let seq = 0

export default function App() {
  const [authed, setAuthed] = useState(null)
  const [theme, setTheme] = useState(() => localStorage.getItem('ez-theme') || 'dark')
  const [pages, setPages] = useState([])
  const [activeId, setActiveId] = useState(null)
  const [analyzing, setAnalyzing] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [error, setError] = useState(null)
  const [codecs, setCodecs] = useState([])
  // Requests the server flagged as already-downloaded, waiting on the user's answer.
  // A queue, not a single value: picking 10 playlist items can raise 10 at once.
  const [dupes, setDupes] = useState([])
  const timers = useRef(new Map())

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('ez-theme', theme)
  }, [theme])

  useEffect(() => {
    checkAuth().then(setAuthed).catch(() => setAuthed(false))
  }, [])

  useEffect(() => {
    if (authed) getCodecs().then(setCodecs).catch(() => setCodecs([]))
  }, [authed])

  useEffect(() => () => timers.current.forEach((t) => t.close()), [])

  const active = pages.find((p) => p.id === activeId) || null

  function patchJob(job) {
    setPages((prev) =>
      prev.map((p) => ({ ...p, jobs: p.jobs.map((j) => (j.id === job.id ? { ...j, ...job } : j)) })),
    )
  }

  function handleAuthError(e) {
    if (e && e.status === 401) {
      setAuthed(false)
      return true
    }
    return false
  }

  // Poll a job until it settles. Polling (not SSE) because the Cloudflare tunnel
  // buffers event streams — see Frontend notes.
  function track(id) {
    if (timers.current.has(id)) return
    let timer = null
    const stop = () => {
      if (timer) clearInterval(timer)
      timers.current.delete(id)
    }
    const tick = async () => {
      try {
        const res = await fetch(`/api/jobs/${id}`)
        if (res.status === 401) {
          stop()
          setAuthed(false)
          return
        }
        if (res.status === 404) {
          stop()
          return
        }
        if (!res.ok) return
        const job = await res.json()
        patchJob(job)
        if (['COMPLETED', 'FAILED', 'CANCELED'].includes(job.status)) stop()
      } catch {
        /* transient — keep polling */
      }
    }
    timer = setInterval(tick, 800)
    timers.current.set(id, { close: stop })
    tick()
  }

  async function onAnalyze(url) {
    const existing = pages.find((p) => p.url === url)
    if (existing) {
      setActiveId(existing.id)
      return
    }
    setError(null)
    setAnalyzing(true)
    try {
      const analysis = await analyze(url)
      const page = { id: `p${++seq}`, url, analysis, jobs: [] }
      setPages((prev) => [...prev, page])
      setActiveId(page.id)
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not analyze that URL')
    } finally {
      setAnalyzing(false)
    }
  }

  /** Re-read one link's available formats. Only this tab changes. */
  async function onRefresh() {
    if (!active) return
    setError(null)
    setRefreshing(true)
    try {
      const analysis = await analyze(active.url)
      setPages((prev) => prev.map((p) => (p.id === active.id ? { ...p, analysis } : p)))
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not refresh the formats')
    } finally {
      setRefreshing(false)
    }
  }

  /** Attach a job to a page's list (no duplicate rows) and start polling it. */
  function adopt(pageId, job) {
    setPages((prev) =>
      prev.map((p) => (p.id === pageId && !p.jobs.some((j) => j.id === job.id)
        ? { ...p, jobs: [job, ...p.jobs] }
        : p)),
    )
    track(job.id)
  }

  async function onStart(request, force = false) {
    setError(null)
    const pageId = activeId
    try {
      const res = await startJob(request, force)
      if (res.duplicate) {
        setDupes((q) => [...q, { request, job: res.job, pageId }])
        return
      }
      adopt(pageId, res)
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not start the download')
    }
  }

  /** User chose to download it again anyway. */
  async function confirmDupe() {
    const [head, ...rest] = dupes
    setDupes(rest)
    await onStart(head.request, true)
  }

  /** User kept the copy the server already has — show it instead of re-fetching. */
  function keepExisting() {
    const [head, ...rest] = dupes
    setDupes(rest)
    adopt(head.pageId, head.job)
  }

  /** Clear only this link's finished files from the server. */
  async function onClear() {
    if (!active) return
    const ids = active.jobs.filter((j) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(j.status)).map((j) => j.id)
    if (ids.length === 0) return
    setClearing(true)
    try {
      await clearJobs(ids)
      const gone = new Set(ids)
      setPages((prev) => prev.map((p) => (p.id === active.id ? { ...p, jobs: p.jobs.filter((j) => !gone.has(j.id)) } : p)))
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not clear downloads')
    } finally {
      setClearing(false)
    }
  }

  // The server deleted the file — drop the row so the list matches reality.
  const onExpired = useCallback((jobId) => {
    setPages((prev) => prev.map((p) => ({ ...p, jobs: p.jobs.filter((j) => j.id !== jobId) })))
  }, [])

  function closePage(id) {
    setPages((prev) => {
      const next = prev.filter((p) => p.id !== id)
      if (id === activeId) setActiveId(next.length ? next[next.length - 1].id : null)
      return next
    })
  }

  async function onLogout() {
    await apiLogout()
    setPages([])
    setActiveId(null)
    setAuthed(false)
  }

  if (authed === null) {
    return <div className="app"><div className="container loading muted">Loading…</div></div>
  }
  if (!authed) return <Login onSuccess={() => setAuthed(true)} />

  const dupe = dupes[0]

  return (
    <div className="app">
      <div className="container">
        <Header theme={theme} onToggleTheme={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))} onLogout={onLogout} />
        <UrlBar onAnalyze={onAnalyze} analyzing={analyzing} />
        {error && <div className="error">{error}</div>}

        <PageTabs pages={pages} activeId={activeId} onSelect={setActiveId} onClose={closePage} />

        {active && (
          active.analysis.playlist
            ? <PlaylistPanel analysis={active.analysis} onStart={onStart} codecs={codecs} />
            : <MediaPanel
                analysis={active.analysis}
                onStart={onStart}
                codecs={codecs}
                onRefresh={onRefresh}
                refreshing={refreshing}
              />
        )}

        {active && (
          <DownloadsPanel jobs={active.jobs} onClear={onClear} onExpired={onExpired} clearing={clearing} />
        )}
      </div>

      {dupe && (
        <ConfirmDialog
          title="You already have this one"
          message={`"${dupe.job.title || dupe.request.title || 'This file'}" was already downloaded with exactly these settings.`}
          detail={
            dupe.job.status === 'COMPLETED'
              ? 'It is still on the server and ready to save — downloading it again costs bandwidth and disk for nothing.'
              : 'It is downloading right now. Starting a second copy would only slow both down.'
          }
          cancelLabel="Use the existing one"
          confirmLabel="Download again"
          onCancel={keepExisting}
          onConfirm={confirmDupe}
        />
      )}

      <footer className="foot muted">by PredatorFX · for ChaitusMedia Team use</footer>
    </div>
  )
}
