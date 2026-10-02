import { useCallback, useEffect, useRef, useState } from 'react'
import { fileUrl } from './api'

/** A finished file waits this long for the user to say where it goes, then saves itself. */
export const GRACE_SECONDS = 30

/**
 * Folder saves that may run side by side. They are the user's own disk and bandwidth, so
 * unlike the server's download queue there is no reason to go one at a time. The cap is
 * only there because an HTTP/1.1 page gets 6 connections per host, and the progress
 * polling needs a couple of them: with six big saves open the cards would stop updating.
 */
const MAX_PARALLEL_SAVES = 4

/**
 * Choosing a folder needs the File System Access API: Chrome or Edge, on https or
 * localhost. Everywhere else the browser's own Downloads folder is the only target.
 */
export const canPickFolder = typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function'

// ---- remembering each download's folder ----------------------------------------------
// A directory handle can't go in localStorage; IndexedDB is the one place it survives.
// One entry per download, keyed by the job's id, so every card keeps its own choice.

function idb(mode, run) {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('ez-autosave', 1)
    open.onupgradeneeded = () => open.result.createObjectStore('kv')
    open.onerror = () => reject(open.error)
    open.onsuccess = () => {
      const db = open.result
      const tx = db.transaction('kv', mode)
      const req = run(tx.objectStore('kv'))
      tx.oncomplete = () => { db.close(); resolve(req.result) }
      tx.onerror = tx.onabort = () => { db.close(); reject(tx.error) }
    }
  })
}
const storeFolder = (jobId, h) => idb('readwrite', (s) => (h ? s.put(h, 'folder:' + jobId) : s.delete('folder:' + jobId))).catch(() => {})
const forgetKey = (key) => idb('readwrite', (s) => s.delete(key)).catch(() => {})
/** Everything remembered as [key, handle] — including keys older versions left behind, so they can be swept. */
const loadFolders = async () => {
  try {
    const keys = await idb('readonly', (s) => s.getAllKeys())
    const handles = await idb('readonly', (s) => s.getAll())
    return keys.map((k, i) => [String(k), handles[i]])
  } catch {
    return []
  }
}

const granted = async (h) => (await h.queryPermission({ mode: 'readwrite' })) === 'granted'
// Re-asking needs a click (user activation), so only ever call this from a click handler.
const ask = async (h) => (await h.requestPermission({ mode: 'readwrite' })) === 'granted'

// ---- the Auto-save switch --------------------------------------------------------------
const ON_KEY = 'ez-autosave'
const readOn = () => { try { return localStorage.getItem(ON_KEY) !== 'off' } catch { return true } }

// ---- one save per file, even with two tabs open -----------------------------------
// A second tab restores the same finished jobs and starts its own countdown, so without
// this every file would download twice. Whoever starts saving first leaves a note here
// and the other tab skips it.

const NOTES = 'ez-autosaved'
const readNotes = () => { try { return JSON.parse(localStorage.getItem(NOTES) || '[]') } catch { return [] } }
const writeNotes = (ids) => { try { localStorage.setItem(NOTES, JSON.stringify(ids.slice(-100))) } catch { /* blocked or full: worst case is one duplicate */ } }
const savedElsewhere = (id) => readNotes().includes(id)
const noteSaved = (id) => writeNotes([...readNotes().filter((x) => x !== id), id])
const forgetSaved = (id) => writeNotes(readNotes().filter((x) => x !== id))

// ---- writing the file ---------------------------------------------------------------

/** "Clip.mp4" -> "Clip (1).mp4" when that name is taken, like the browser does. */
async function freeName(dir, name) {
  const dot = name.lastIndexOf('.')
  const stem = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : ''
  for (let i = 0; ; i++) {
    const candidate = i ? `${stem} (${i})${ext}` : name
    try {
      await dir.getFileHandle(candidate)
    } catch (e) {
      if (e.name === 'NotFoundError') return candidate
      throw e
    }
  }
}

/**
 * Stream the finished file from the server straight into the folder. Streaming, not a
 * Blob: these are multi-GB 4K files and a Blob would have to fit in the tab's memory.
 */
async function writeToFolder(dir, job, onProgress) {
  const res = await fetch(fileUrl(job.id), { cache: 'no-store' })
  if (!res.ok || !res.body) throw Object.assign(new Error(`the server answered ${res.status}`), { http: res.status })
  const name = await freeName(dir, job.fileName || `${job.id}.mp4`)
  const total = Number(res.headers.get('Content-Length')) || job.fileSize || 0
  let got = 0
  const count = new TransformStream({
    transform(chunk, ctl) {
      got += chunk.byteLength
      if (total) onProgress(got / total)
      ctl.enqueue(chunk)
    },
  })
  const file = await dir.getFileHandle(name, { create: true })
  try {
    await res.body.pipeThrough(count).pipeTo(await file.createWritable())
  } catch (e) {
    await dir.removeEntry(name).catch(() => {}) // never leave an empty or half-written file behind
    throw e
  }
}

/** The browser's own download: lands in its Downloads folder with no prompt of ours. */
function downloadToBrowser(job) {
  const a = document.createElement('a')
  a.href = fileUrl(job.id)
  a.download = job.fileName || ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/**
 * Saves finished downloads without being asked — when the Auto-save switch is on.
 *
 * Every DOWNLOAD (each card) has its own folder choice:
 *  - A folder is chosen -> that file goes there the moment it is ready, no countdown. Its
 *                          Save file button is off: there is nothing left to do.
 *  - No folder          -> a GRACE_SECONDS countdown. Pressing Save file, or choosing a
 *                          folder, ends it early; otherwise the file lands in the
 *                          browser's Downloads folder when it runs out.
 * With the switch off none of this runs: files wait for Save file, as they always did.
 *
 * Lives once, in App: the Downloads panel is mounted twice (desktop column and mobile
 * sheet), so doing this inside a card would save every file twice.
 */
export function useAutoSave({ jobs, enabled, markSaved }) {
  const [on, setOnState] = useState(readOn)
  const [folders, setFolders] = useState({}) // job id -> { jobId, handle, name, granted }
  const [ready, setReady] = useState(false) // the remembered folders have been looked up
  const [state, setState] = useState({}) // job id -> { phase: wait|saving|saved|failed, ... }
  const timers = useRef(new Map()) // job id -> countdown timeout (exactly the jobs in 'wait')
  const seen = useRef(new Set()) // finished jobs already handed to begin()
  const knownJobs = useRef(new Set())
  const slots = useRef({ busy: 0, waiting: [] })
  const jobsRef = useRef(jobs)
  const foldersRef = useRef({})
  jobsRef.current = jobs
  const active = enabled && on

  const setOn = useCallback((value) => {
    setOnState(value)
    try { localStorage.setItem(ON_KEY, value ? 'on' : 'off') } catch { /* private window: just not remembered */ }
  }, [])
  const put = useCallback((id, s) => setState((m) => ({ ...m, [id]: s })), [])
  const stop = useCallback((id) => {
    clearTimeout(timers.current.get(id))
    timers.current.delete(id)
  }, [])
  const applyFolder = useCallback((jobId, f) => {
    const next = { ...foldersRef.current }
    if (f) next[jobId] = { ...f, jobId }
    else delete next[jobId]
    foldersRef.current = next
    setFolders(next)
  }, [])

  // Folder saves run side by side, up to the cap; any beyond it wait their turn.
  const withSlot = useCallback((task) => {
    const s = slots.current
    const go = async () => {
      s.busy++
      try { await task() } finally {
        s.busy--
        s.waiting.shift()?.()
      }
    }
    if (s.busy < MAX_PARALLEL_SAVES) go()
    else s.waiting.push(go)
  }, [])

  const run = useCallback((job, dir) => {
    stop(job.id)
    noteSaved(job.id)
    const where = dir ? dir.name : null
    const task = async () => {
      let last = 0
      const progress = (pct) => {
        if (Date.now() - last > 250) { // ~4 repaints a second, not one per 64 KB chunk
          last = Date.now()
          put(job.id, { phase: 'saving', where, pct })
        }
      }
      put(job.id, { phase: 'saving', where, pct: 0 })
      try {
        if (dir) await writeToFolder(dir.handle, job, progress)
        else downloadToBrowser(job)
        put(job.id, { phase: 'saved', where })
      } catch (e) {
        if (!dir || e.http) { // a server problem: the browser would hit the same wall
          forgetSaved(job.id)
          put(job.id, { phase: 'failed', error: e.message })
          return
        }
        // The folder let us down (access withdrawn, disk full, a name it won't take).
        // The finished file must not be lost, so the browser gets it instead.
        downloadToBrowser(job)
        put(job.id, { phase: 'saved', where: null, fallbackFrom: dir.name })
        const f = foldersRef.current[dir.jobId]
        if (f && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) {
          applyFolder(dir.jobId, { ...f, granted: false }) // the card should say "needs access"
        }
      }
      markSaved(job.id)
    }
    if (dir) {
      put(job.id, { phase: 'saving', where, pct: 0, queued: slots.current.busy >= MAX_PARALLEL_SAVES })
      withSlot(task)
    } else {
      task() // the browser's own download is instant and keeps its own queue
    }
  }, [applyFolder, markSaved, put, stop, withSlot])

  // Every automatic save goes through here, so another tab that got there first wins.
  // (Pressing Save file is a decision, not an automation: it always goes ahead.)
  const auto = useCallback((job, dir) => {
    if (!savedElsewhere(job.id)) return run(job, dir)
    stop(job.id)
    put(job.id, { phase: 'saved', where: null })
    markSaved(job.id)
  }, [markSaved, put, run, stop])

  const begin = useCallback(async (job) => {
    const f = foldersRef.current[job.id]
    if (f) {
      if (await granted(f.handle).catch(() => false)) return auto(job, f)
      // The browser has taken the permission back (it does that between visits).
      // Say so, rather than showing a folder as ready when it isn't.
      if (f.granted) applyFolder(job.id, { ...f, granted: false })
    }
    put(job.id, { phase: 'wait', deadline: Date.now() + GRACE_SECONDS * 1000 })
    timers.current.set(job.id, setTimeout(() => auto(job, null), GRACE_SECONDS * 1000))
  }, [applyFolder, auto, put])

  // Look up the folders remembered from last time — one per download still on the page —
  // and sweep the rest: downloads cleared while the page was closed, and the per-link-tab
  // and shared-folder entries that older versions left behind.
  useEffect(() => {
    let live = true
    ;(async () => {
      if (canPickFolder) {
        const present = new Set(jobsRef.current.map((j) => j.id))
        for (const [key, handle] of await loadFolders()) {
          const id = key.startsWith('folder:') ? key.slice('folder:'.length) : null
          if (!id || !present.has(id) || !handle || !handle.name) { forgetKey(key); continue }
          if (live) applyFolder(id, { handle, name: handle.name, granted: await granted(handle).catch(() => false) })
        }
      }
      if (live) setReady(true)
    })()
    return () => { live = false }
  }, [applyFolder])

  // A download that is gone (cleared, its tab closed) takes its folder choice with it.
  useEffect(() => {
    const ids = new Set(jobs.map((j) => j.id))
    for (const id of knownJobs.current) {
      if (!ids.has(id)) {
        storeFolder(id, null)
        if (foldersRef.current[id]) applyFolder(id, null)
      }
    }
    knownJobs.current = ids
  }, [jobs, applyFolder])

  useEffect(() => {
    // Drop countdowns for files that are gone (cleared, tab closed, retried, logged out) —
    // and all of them when the switch goes off.
    const finished = new Set(jobs.filter((j) => j.status === 'COMPLETED').map((j) => j.id))
    for (const id of [...timers.current.keys()]) {
      if (active && finished.has(id)) continue
      stop(id)
      if (!active) { // forget it entirely, so switching back on starts the countdown afresh
        seen.current.delete(id)
        setState((m) => { const rest = { ...m }; delete rest[id]; return rest })
      }
    }
    if (!active || !ready) return
    for (const job of jobs) {
      if (job.status === 'COMPLETED') {
        if (!job.saved && !seen.current.has(job.id)) {
          seen.current.add(job.id)
          begin(job)
        }
      } else if (seen.current.delete(job.id)) { // retried: its next completion saves again
        forgetSaved(job.id)
        setState((m) => { const rest = { ...m }; delete rest[job.id]; return rest })
      }
    }
  }, [jobs, active, ready, begin, stop])

  // A file already counting down goes straight to the folder it was waiting for.
  const releaseIfWaiting = useCallback((jobId) => {
    if (!timers.current.has(jobId)) return
    const job = jobsRef.current.find((j) => j.id === jobId)
    if (job) auto(job, foldersRef.current[jobId])
  }, [auto])

  const pick = useCallback(async (jobId) => {
    let handle
    try {
      handle = await window.showDirectoryPicker({ id: 'ez-save', mode: 'readwrite', startIn: 'downloads' })
    } catch {
      return // dialog dismissed
    }
    storeFolder(jobId, handle)
    applyFolder(jobId, { handle, name: handle.name, granted: true }) // choosing it is itself the grant
    releaseIfWaiting(jobId)
  }, [applyFolder, releaseIfWaiting])

  /** Re-grant a remembered folder after the browser has forgotten the permission. */
  const allow = useCallback(async (jobId) => {
    const f = foldersRef.current[jobId]
    if (!f) return
    let ok = false
    try { ok = await ask(f.handle) } catch { /* refused */ }
    applyFolder(jobId, { ...f, granted: ok })
    if (ok) releaseIfWaiting(jobId)
  }, [applyFolder, releaseIfWaiting])

  const clear = useCallback((jobId) => {
    storeFolder(jobId, null)
    applyFolder(jobId, null)
  }, [applyFolder])

  /** The Save file button — only offered with no folder set, so the browser's own download does the work. */
  const saved = useCallback((job) => {
    stop(job.id)
    noteSaved(job.id)
    put(job.id, { phase: 'saved', where: null })
    markSaved(job.id)
  }, [markSaved, put, stop])

  return { on, setOn, folders, canPick: canPickFolder, state, pick, allow, clear, saved }
}
