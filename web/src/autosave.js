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
const DEFAULT_KEY = 'folder:*default*'
const storeFolder = (jobId, h) => idb('readwrite', (s) => (h ? s.put(h, 'folder:' + jobId) : s.delete('folder:' + jobId))).catch(() => {})
const storeDefault = (h) => idb('readwrite', (s) => (h ? s.put(h, DEFAULT_KEY) : s.delete(DEFAULT_KEY))).catch(() => {})
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
 * Bytes are handed to the file in 1 MB pieces. Each write() on a
 * FileSystemWritableFileStream is a round trip to the browser process, and a fetch body
 * arrives in ~64 KB chunks, so writing them straight through spends most of its time in
 * that overhead: measured on this machine, 180 MB/s piping chunks through as they come
 * against 550 MB/s buffered into 1 MB writes. 4 MB and 8 MB buffers measured no better.
 */
const WRITE_CHUNK = 1 << 20

/**
 * Stream the finished file from the server straight into the folder. Streamed, never a
 * Blob: these are multi-GB 4K files and a Blob would have to fit in the tab's memory.
 */
async function writeToFolder(dir, job, onProgress) {
  const res = await fetch(fileUrl(job.id), { cache: 'no-store' })
  if (!res.ok || !res.body) throw Object.assign(new Error(`the server answered ${res.status}`), { http: res.status })
  const name = await freeName(dir, job.fileName || `${job.id}.mp4`)
  const total = Number(res.headers.get('Content-Length')) || job.fileSize || 0
  const file = await dir.getFileHandle(name, { create: true })
  const writer = await file.createWritable()
  const reader = res.body.getReader()
  let buf = new Uint8Array(WRITE_CHUNK)
  let held = 0
  let got = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      got += value.byteLength
      if (total) onProgress(got / total)
      let off = 0
      while (off < value.byteLength) {
        const take = Math.min(WRITE_CHUNK - held, value.byteLength - off)
        buf.set(value.subarray(off, off + take), held)
        held += take
        off += take
        if (held === WRITE_CHUNK) {
          await writer.write(buf)
          held = 0
          buf = new Uint8Array(WRITE_CHUNK) // the previous one is still in flight
        }
      }
    }
    if (held) await writer.write(buf.subarray(0, held))
    await writer.close()
  } catch (e) {
    await writer.abort().catch(() => {})
    await dir.removeEntry(name).catch(() => {}) // never leave an empty or half-written file behind
    throw e
  }
}

/**
 * Hand the file to the browser's own downloader.
 *
 * Nothing here can be awaited or checked: an anchor click returns immediately, and the
 * page is never told whether the file was written, where it went, or whether the person
 * cancelled. Chrome's "Ask where to save each file before downloading" turns this into a
 * Save As dialog, which is why this path can never be the silent one — only a folder
 * handle can. Callers must not report this as saved.
 */
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
  const [fallback, setFallback] = useState(false) // on, but with no folder to save into
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

  const defaultRef = useRef(null)

  /** This download's own folder, else the one Auto-save was given when it was switched on. */
  const folderFor = useCallback((jobId) => foldersRef.current[jobId] || foldersRef.current[DEFAULT_KEY], [])

  const applyDefault = useCallback((f) => {
    const next = { ...foldersRef.current }
    if (f) next[DEFAULT_KEY] = { ...f, jobId: DEFAULT_KEY }
    else delete next[DEFAULT_KEY]
    foldersRef.current = next
    defaultRef.current = next[DEFAULT_KEY] || null
    setFolders(next)
  }, [])

  /**
   * Switching Auto-save ON asks for a folder, once. That is the whole point: a page can
   * only write a file without a dialog through a folder it has been granted, so without
   * one every "automatic" save is at the mercy of the browser's own download prompt.
   * Dismissing the picker still turns Auto-save on — it just falls back to handing files
   * to the browser, and says so.
   */
  const setOn = useCallback(async (value) => {
    setOnState(value)
    try { localStorage.setItem(ON_KEY, value ? 'on' : 'off') } catch { /* private window: just not remembered */ }
    if (!value) { setFallback(false); return }
    if (!canPickFolder || defaultRef.current) return
    let handle
    try {
      handle = await window.showDirectoryPicker({ id: 'ez-save', mode: 'readwrite', startIn: 'downloads' })
    } catch {
      setFallback(true) // dismissed — the header explains what that costs
      return
    }
    storeDefault(handle)
    applyDefault({ handle, name: handle.name, granted: true })
    setFallback(false)
  }, [applyDefault])
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
      if (!dir) {
        // Handed to the browser, which tells us nothing back. Deliberately NOT marked as
        // saved: the person may still be looking at a Save As dialog, or may have
        // cancelled it, and the unsaved-file warnings have to keep protecting them.
        downloadToBrowser(job)
        put(job.id, { phase: 'handed' })
        return
      }
      try {
        await writeToFolder(dir.handle, job, progress)
        put(job.id, { phase: 'saved', where })
        markSaved(job.id) // the only path that knows the bytes really landed
      } catch (e) {
        if (e.http) { // a server problem: the browser would hit the same wall
          forgetSaved(job.id)
          put(job.id, { phase: 'failed', error: e.message })
          return
        }
        // The folder let us down (access withdrawn, disk full, a name it won't take).
        // The finished file must not be lost, so the browser gets it instead.
        forgetSaved(job.id)
        downloadToBrowser(job)
        put(job.id, { phase: 'handed', fallbackFrom: dir.name })
        const f = foldersRef.current[dir.jobId]
        if (f && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) {
          if (dir.jobId === DEFAULT_KEY) applyDefault({ ...f, granted: false })
          else applyFolder(dir.jobId, { ...f, granted: false }) // the card says "needs access"
        }
      }
    }
    if (dir) {
      put(job.id, { phase: 'saving', where, pct: 0, queued: slots.current.busy >= MAX_PARALLEL_SAVES })
      withSlot(task)
    } else {
      task() // the browser's own download is instant and keeps its own queue
    }
  }, [applyDefault, applyFolder, markSaved, put, stop, withSlot])

  // Every automatic save goes through here, so another tab that got there first wins.
  // (Pressing Save file is a decision, not an automation: it always goes ahead.)
  const auto = useCallback((job, dir) => {
    if (!savedElsewhere(job.id)) return run(job, dir)
    stop(job.id) // another tab is already saving this one
    put(job.id, { phase: 'elsewhere' })
  }, [put, run, stop])

  const begin = useCallback(async (job) => {
    const f = folderFor(job.id)
    if (f) {
      if (await granted(f.handle).catch(() => false)) return auto(job, f)
      // The browser has taken the permission back (it does that between visits).
      // Say so, rather than showing a folder as ready when it isn't.
      if (f.granted) {
        if (f.jobId === DEFAULT_KEY) applyDefault({ ...f, granted: false })
        else applyFolder(job.id, { ...f, granted: false })
      }
    }
    put(job.id, { phase: 'wait', deadline: Date.now() + GRACE_SECONDS * 1000 })
    timers.current.set(job.id, setTimeout(() => auto(job, null), GRACE_SECONDS * 1000))
  }, [applyDefault, applyFolder, auto, folderFor, put])

  // Look up the folders remembered from last time — one per download still on the page —
  // and sweep the rest: downloads cleared while the page was closed, and the per-link-tab
  // and shared-folder entries that older versions left behind.
  useEffect(() => {
    let live = true
    ;(async () => {
      if (canPickFolder) {
        const present = new Set(jobsRef.current.map((j) => j.id))
        for (const [key, handle] of await loadFolders()) {
          if (!handle || !handle.name) { forgetKey(key); continue }
          if (key === DEFAULT_KEY) {
            if (live) applyDefault({ handle, name: handle.name, granted: await granted(handle).catch(() => false) })
            continue
          }
          const id = key.startsWith('folder:') ? key.slice('folder:'.length) : null
          if (!id || !present.has(id)) { forgetKey(key); continue }
          if (live) applyFolder(id, { handle, name: handle.name, granted: await granted(handle).catch(() => false) })
        }
      }
      if (live) setReady(true)
    })()
    return () => { live = false }
  }, [applyDefault, applyFolder])

  // A download that is gone (cleared, its tab closed) takes its folder choice with it.
  useEffect(() => {
    const ids = new Set(jobs.map((j) => j.id))
    knownJobs.current.delete(DEFAULT_KEY)
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

  // A file already counting down goes straight to the folder it was waiting for. It must
  // be the EFFECTIVE folder: a card with no choice of its own still inherits Auto-save's,
  // and reading only the card's own entry handed those files to the browser instead.
  const releaseIfWaiting = useCallback((jobId) => {
    if (!timers.current.has(jobId)) return
    const job = jobsRef.current.find((j) => j.id === jobId)
    if (job) auto(job, folderFor(jobId))
  }, [auto, folderFor])

  const pick = useCallback(async (jobId) => {
    let handle
    try {
      handle = await window.showDirectoryPicker({ id: 'ez-save', mode: 'readwrite', startIn: 'downloads' })
    } catch {
      return // dialog dismissed
    }
    storeFolder(jobId, handle)
    applyFolder(jobId, { handle, name: handle.name, granted: true }) // choosing it is itself the grant
    if (!defaultRef.current) { storeDefault(handle); applyDefault({ handle, name: handle.name, granted: true }) }
    setFallback(false)
    releaseIfWaiting(jobId)
  }, [applyDefault, applyFolder, releaseIfWaiting])

  /** Re-grant a remembered folder after the browser has forgotten the permission. */
  const allow = useCallback(async (jobId) => {
    const f = folderFor(jobId)
    if (!f) return
    let ok = false
    try { ok = await ask(f.handle) } catch { /* refused */ }
    if (f.jobId === DEFAULT_KEY) applyDefault({ ...f, granted: ok })
    else applyFolder(jobId, { ...f, granted: ok })
    if (ok) releaseIfWaiting(jobId)
  }, [applyDefault, applyFolder, folderFor, releaseIfWaiting])

  /** Drop this download's own choice; it goes back to Auto-save's folder. */
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

  return {
    on,
    setOn,
    folders,
    folderFor,
    defaultFolder: folders[DEFAULT_KEY] || null,
    /** On, but with nothing to save into — the browser will be asked to do it instead. */
    fallback: on && canPickFolder && !folders[DEFAULT_KEY],
    dismissed: fallback,
    canPick: canPickFolder,
    state,
    pick,
    allow,
    clear,
    saved,
  }
}
