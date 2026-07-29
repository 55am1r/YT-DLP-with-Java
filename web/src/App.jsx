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
const STORE_KEY = 'ez-session-v1'
const ACTIVE = new Set(['QUEUED', 'CHECKING_UPDATES', 'ANALYZING', 'DOWNLOADING', 'PAUSED', 'PROCESSING', 'COMPRESSING', 'PACKAGING'])
const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELED']
const isMobile = () => window.innerWidth < 750

/** Restore the previous session (tabs, their settings, and download history). */
function loadSession() {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return { pages: [], activeId: null }
    const s = JSON.parse(raw)
    const pages = Array.isArray(s.pages) ? s.pages : []
    // Keep the numbering monotonic so a restored tab id never collides with a new one.
    pages.forEach((p) => {
      const n = parseInt(String(p.id).replace(/^p/, ''), 10)
      if (Number.isFinite(n) && n > seq) seq = n
    })
    return { pages, activeId: s.activeId ?? (pages[pages.length - 1]?.id ?? null) }
  } catch {
    return { pages: [], activeId: null }
  }
}

export default function App() {
  const [authed, setAuthed] = useState(null)
  const [theme, setTheme] = useState(() => localStorage.getItem('ez-theme') || 'dark')
  const restored = useRef(loadSession())
  const [pages, setPages] = useState(restored.current.pages)
  const [activeId, setActiveId] = useState(restored.current.activeId)
  const [analyzing, setAnalyzing] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [error, setError] = useState(null)
  const [codecs, setCodecs] = useState([])
  const [dupes, setDupes] = useState([])       // duplicate-download prompts, one at a time
  const [closing, setClosing] = useState(null)  // tab pending a close confirmation
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

  // Persist the whole session so a refresh — or a phone reopening the site — comes back
  // to the same tabs, settings and download history.
  useEffect(() => {
    if (authed) {
      try { localStorage.setItem(STORE_KEY, JSON.stringify({ pages, activeId })) } catch { /* quota */ }
    }
  }, [pages, activeId, authed])

  // Warn before a refresh throws away an in-progress download — but only on desktop.
  // On a phone the download keeps running on the server, so there's nothing to lose.
  useEffect(() => {
    const anyActive = pages.some((p) => p.jobs.some((j) => ACTIVE.has(j.status)))
    if (!anyActive) return
    const warn = (e) => {
      if (isMobile()) return
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [pages])

  // Resume polling for any restored job that was still running when we last saw it.
  useEffect(() => {
    if (!authed) return
    restored.current.pages.forEach((p) => p.jobs.forEach((j) => {
      if (ACTIVE.has(j.status)) track(j.id)
    }))
    restored.current = { pages: [], activeId: null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authed])

  useEffect(() => () => timers.current.forEach((t) => t.close()), [])

  const active = pages.find((p) => p.id === activeId) || null

  function patchJob(job) {
    setPages((prev) =>
      prev.map((p) => ({ ...p, jobs: p.jobs.map((j) => (j.id === job.id ? { ...j, ...job } : j)) })),
    )
  }

  function handleAuthError(e) {
    if (e && e.status === 401) { setAuthed(false); return true }
    return false
  }

  // Poll a job until it settles. Polling (not SSE) because the Cloudflare tunnel buffers
  // event streams. A 404 means the server no longer has this job (it was restarted),
  // so the download is marked interrupted — the card then offers Retry.
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
        if (res.status === 401) { stop(); setAuthed(false); return }
        if (res.status === 404) {
          stop()
          setPages((prev) => prev.map((p) => ({
            ...p,
            jobs: p.jobs.map((j) => (j.id === id && ACTIVE.has(j.status)
              ? { ...j, status: 'FAILED', interrupted: true, error: 'Interrupted — the server no longer has this file. Retry to download it again.' }
              : j)),
          })))
          return
        }
        if (!res.ok) return
        const job = await res.json()
        patchJob(job)
        if (TERMINAL.includes(job.status)) stop()
      } catch { /* transient — keep polling */ }
    }
    timer = setInterval(tick, 800)
    timers.current.set(id, { close: stop })
    tick()
  }

  async function onAnalyze(url) {
    const existing = pages.find((p) => p.url === url)
    if (existing) { setActiveId(existing.id); return }
    setError(null)
    setAnalyzing(true)
    try {
      const analysis = await analyze(url)
      // Even a single video gets its own tab, so the layout is consistent.
      const page = { id: `p${++seq}`, url, analysis, jobs: [], config: null }
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

  /** Store a panel's live settings on its tab, so they survive tab-switch and refresh. */
  const saveConfig = useCallback((pageId, config) => {
    setPages((prev) => prev.map((p) => (p.id === pageId ? { ...p, config } : p)))
  }, [])

  function adopt(pageId, job, request) {
    setPages((prev) =>
      prev.map((p) => (p.id === pageId && !p.jobs.some((j) => j.id === job.id)
        ? { ...p, jobs: [{ ...job, request }, ...p.jobs] }
        : p)),
    )
    track(job.id)
  }

  async function onStart(pageId, request, force = false) {
    setError(null)
    try {
      const res = await startJob(request, force)
      if (res.duplicate) { setDupes((q) => [...q, { request, job: res.job, pageId }]); return }
      adopt(pageId, res, request)
    } catch (e) {
      if (!handleAuthError(e)) setError(e.message || 'Could not start the download')
    }
  }

  /** Re-run a finished, failed or interrupted job with the exact settings it used. */
  function retry(pageId, job) {
    if (job.request) onStart(pageId, job.request, true)
  }

  async function confirmDupe() {
    const [head, ...rest] = dupes
    setDupes(rest)
    await onStart(head.pageId, head.request, true)
  }

  function keepExisting() {
    const [head, ...rest] = dupes
    setDupes(rest)
    adopt(head.pageId, head.job, head.request)
  }

  /** Mark that the user has saved this file at least once (for the close guard). */
  const markSaved = useCallback((jobId) => {
    setPages((prev) => prev.map((p) => ({ ...p, jobs: p.jobs.map((j) => (j.id === jobId ? { ...j, saved: true } : j)) })))
  }, [])

  async function onClear() {
    if (!active) return
    const ids = active.jobs.filter((j) => TERMINAL.includes(j.status)).map((j) => j.id)
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

  const onExpired = useCallback((jobId) => {
    setPages((prev) => prev.map((p) => ({ ...p, jobs: p.jobs.filter((j) => j.id !== jobId) })))
  }, [])

  /** Remove a tab and delete its files from the server. */
  function removePage(id) {
    const page = pages.find((p) => p.id === id)
    if (page) {
      const ids = page.jobs.filter((j) => TERMINAL.includes(j.status)).map((j) => j.id)
      if (ids.length) clearJobs(ids).catch(() => {})
      page.jobs.forEach((j) => timers.current.get(j.id)?.close())
    }
    setPages((prev) => {
      const next = prev.filter((p) => p.id !== id)
      if (id === activeId) setActiveId(next.length ? next[next.length - 1].id : null)
      return next
    })
  }

  // Closing a tab throws away its server files. Confirm first if a download is running,
  // or a finished file was never saved — otherwise just close.
  function closePage(id) {
    const page = pages.find((p) => p.id === id)
    const risky = page && page.jobs.some((j) => ACTIVE.has(j.status) || (j.status === 'COMPLETED' && !j.saved))
    if (risky) setClosing(id)
    else removePage(id)
  }

  async function onLogout() {
    await apiLogout()
    localStorage.removeItem(STORE_KEY)
    timers.current.forEach((t) => t.close())
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
          <div className="workspace">
            <div className="work-main">
              {active.analysis.playlist ? (
                <PlaylistPanel
                  key={active.id}
                  analysis={active.analysis}
                  config={active.config}
                  onConfig={(c) => saveConfig(active.id, c)}
                  onStart={(req) => onStart(active.id, req)}
                  codecs={codecs}
                />
              ) : (
                <MediaPanel
                  key={active.id}
                  analysis={active.analysis}
                  config={active.config}
                  onConfig={(c) => saveConfig(active.id, c)}
                  onStart={(req) => onStart(active.id, req)}
                  codecs={codecs}
                  onRefresh={onRefresh}
                  refreshing={refreshing}
                />
              )}
            </div>
            <DownloadsPanel
              jobs={active.jobs}
              onClear={onClear}
              onExpired={onExpired}
              onRetry={(job) => retry(active.id, job)}
              onSaved={markSaved}
              clearing={clearing}
            />
          </div>
        )}
      </div>

      {dupe && (
        <ConfirmDialog
          title="You already have this one"
          message={`"${dupe.job.title || dupe.request.title || 'This file'}" was already downloaded with exactly these settings.`}
          detail={dupe.job.status === 'COMPLETED'
            ? 'It is still on the server and ready to save — downloading it again costs bandwidth and disk for nothing.'
            : 'It is downloading right now. Starting a second copy would only slow both down.'}
          cancelLabel="Use the existing one"
          confirmLabel="Download again"
          onCancel={keepExisting}
          onConfirm={confirmDupe}
        />
      )}

      {closing && (
        <ConfirmDialog
          title="Close this tab?"
          message="Closing it removes this link's files from the server."
          detail="A download is still running or a finished file hasn't been saved yet — it will be lost."
          cancelLabel="Keep the tab"
          confirmLabel="Close and delete"
          onCancel={() => setClosing(null)}
          onConfirm={() => { const id = closing; setClosing(null); removePage(id) }}
        />
      )}

      <footer className="foot muted">by PredatorFX · for ChaitusMedia Team use</footer>
    </div>
  )
}
