import { useCallback, useEffect, useRef, useState } from 'react'
import { fileUrl } from './api'

/** A finished file waits this long for the user to say where it goes, then saves itself. */
export const GRACE_SECONDS = 30

/**
 * Choosing a folder needs the File System Access API: Chrome or Edge, on https or
 * localhost. Everywhere else the browser's own Downloads folder is the only target.
 */
export const canPickFolder = typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function'

// ---- remembering the folder ---------------------------------------------------------
// A directory handle can't go in localStorage; IndexedDB is the one place it survives.

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
const loadFolder = () => idb('readonly', (s) => s.get('folder')).catch(() => null)
const storeFolder = (h) => idb('readwrite', (s) => (h ? s.put(h, 'folder') : s.delete('folder'))).catch(() => {})

const granted = async (h) => (await h.queryPermission({ mode: 'readwrite' })) === 'granted'
// Re-asking needs a click (user activation), so only ever call this from a click handler.
const ask = async (h) => (await h.requestPermission({ mode: 'readwrite' })) === 'granted'

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
 * Saves finished downloads without being asked.
 *
 *  - A folder is chosen -> the file goes there the moment it is ready, no countdown.
 *  - No folder          -> a GRACE_SECONDS countdown. Pressing Save file, or choosing a
 *                          folder, ends it early; otherwise the file lands in the
 *                          browser's Downloads folder when it runs out.
 *
 * Lives once, in App: the Downloads panel is mounted twice (desktop column and mobile
 * sheet), so doing this inside a card would save every file twice.
 */
export function useAutoSave({ jobs, enabled, markSaved }) {
  const [folder, setFolder] = useState(null) // { handle, name, granted }
  const [ready, setReady] = useState(false) // the remembered folder has been looked up
  const [state, setState] = useState({}) // job id -> { phase: wait|saving|saved|failed, ... }
  const timers = useRef(new Map()) // job id -> countdown timeout (exactly the jobs in 'wait')
  const seen = useRef(new Set()) // finished jobs already handed to begin()
  const jobsRef = useRef(jobs)
  const folderRef = useRef(null)
  const queue = useRef(Promise.resolve())
  jobsRef.current = jobs

  const put = useCallback((id, s) => setState((m) => ({ ...m, [id]: s })), [])
  const stop = useCallback((id) => {
    clearTimeout(timers.current.get(id))
    timers.current.delete(id)
  }, [])
  const applyFolder = useCallback((f) => { folderRef.current = f; setFolder(f) }, [])

  // One save at a time: two multi-GB writes in parallel only fight each other for the disk.
  const run = useCallback((job, dir) => {
    stop(job.id)
    noteSaved(job.id)
    const where = dir ? dir.name : null
    put(job.id, { phase: 'saving', where, pct: 0 })
    queue.current = queue.current.then(async () => {
      let last = 0
      const progress = (pct) => {
        if (Date.now() - last > 250) { // ~4 repaints a second, not one per 64 KB chunk
          last = Date.now()
          put(job.id, { phase: 'saving', where, pct })
        }
      }
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
        if (folderRef.current && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) {
          applyFolder({ ...folderRef.current, granted: false }) // the chip should say "needs access"
        }
      }
      markSaved(job.id)
    })
  }, [applyFolder, markSaved, put, stop])

  // Every automatic save goes through here, so another tab that got there first wins.
  // (Pressing Save file is a decision, not an automation: it always goes ahead.)
  const auto = useCallback((job, dir) => {
    if (!savedElsewhere(job.id)) return run(job, dir)
    stop(job.id)
    put(job.id, { phase: 'saved', where: null })
    markSaved(job.id)
  }, [markSaved, put, run, stop])

  // Everything still counting down goes to `dir` now: choosing a folder is the answer
  // those files were waiting for.
  const flush = useCallback((dir) => {
    for (const id of [...timers.current.keys()]) {
      const job = jobsRef.current.find((j) => j.id === id)
      if (job) auto(job, dir)
    }
  }, [auto])

  const begin = useCallback(async (job) => {
    const f = folderRef.current
    if (f) {
      if (await granted(f.handle).catch(() => false)) return auto(job, f)
      // The browser has taken the permission back (it does that between visits).
      // Say so, rather than showing a folder as ready when it isn't.
      if (f.granted) applyFolder({ ...f, granted: false })
    }
    put(job.id, { phase: 'wait', deadline: Date.now() + GRACE_SECONDS * 1000 })
    timers.current.set(job.id, setTimeout(() => auto(job, null), GRACE_SECONDS * 1000))
  }, [applyFolder, auto, put])

  // Look up the folder remembered from last time.
  useEffect(() => {
    let live = true
    ;(async () => {
      const handle = canPickFolder ? await loadFolder() : null
      if (handle && live) {
        applyFolder({ handle, name: handle.name, granted: await granted(handle).catch(() => false) })
      }
      if (live) setReady(true)
    })()
    return () => { live = false }
  }, [applyFolder])

  useEffect(() => {
    // Drop countdowns for files that are gone: cleared, tab closed, retried, logged out.
    const finished = new Set(jobs.filter((j) => j.status === 'COMPLETED').map((j) => j.id))
    for (const id of [...timers.current.keys()]) if (!enabled || !finished.has(id)) stop(id)
    if (!enabled || !ready) return
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
  }, [jobs, enabled, ready, begin, stop])

  const pick = useCallback(async () => {
    let handle
    try {
      handle = await window.showDirectoryPicker({ id: 'ez-save', mode: 'readwrite', startIn: 'downloads' })
    } catch {
      return // dialog dismissed
    }
    storeFolder(handle)
    const next = { handle, name: handle.name, granted: true } // choosing it is itself the grant
    applyFolder(next)
    flush(next)
  }, [applyFolder, flush])

  /** Re-grant a remembered folder after the browser has forgotten the permission. */
  const allow = useCallback(async () => {
    const f = folderRef.current
    if (!f) return
    let ok = false
    try { ok = await ask(f.handle) } catch { /* refused */ }
    const next = { ...f, granted: ok }
    applyFolder(next)
    if (ok) flush(next)
  }, [applyFolder, flush])

  /**
   * Call from the click that starts a download. The browser takes a remembered folder's
   * permission back between visits, and it will only ask again in answer to a click — so
   * ask now, while there is one, instead of failing to at the end with nobody there.
   * Doesn't block the download: it carries on whatever the answer.
   */
  const ensureAccess = useCallback(() => {
    const f = folderRef.current
    if (f && !f.granted) allow() // also sends anything already counting down to the folder
  }, [allow])

  const clear = useCallback(() => {
    storeFolder(null)
    applyFolder(null)
  }, [applyFolder])

  /** The Save file button, when a folder is set. */
  const save = useCallback(async (job) => {
    stop(job.id) // the countdown ends the moment the user acts, whatever happens next
    const f = folderRef.current
    let dir = null
    if (f) {
      try {
        if ((await granted(f.handle)) || (await ask(f.handle))) dir = { ...f, granted: true }
      } catch { /* fall through to the browser */ }
    }
    run(job, dir)
  }, [run, stop])

  /** The Save file button with no folder set: the browser's own download does the work. */
  const saved = useCallback((job) => {
    stop(job.id)
    noteSaved(job.id)
    put(job.id, { phase: 'saved', where: null })
    markSaved(job.id)
  }, [markSaved, put, stop])

  return { folder, canPick: canPickFolder, state, pick, allow, ensureAccess, clear, save, saved }
}
