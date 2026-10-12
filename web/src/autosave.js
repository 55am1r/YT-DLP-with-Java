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

const OFF = { on: false, folder: null }
const TEMPLATE_KEY = 'lock:template'
// A tab remembers its switch and folder together; a handle survives inside an object in IndexedDB.
const storeTab = (pageId, t) => idb('readwrite', (s) => (t && t.on ? s.put({ on: true, handle: t.folder?.handle || null }, 'tab:' + pageId) : s.delete('tab:' + pageId))).catch(() => {})
const storeTemplateRaw = (t) => idb('readwrite', (s) => (t ? s.put({ on: !!t.on, handle: t.folder?.handle || null }, TEMPLATE_KEY) : s.delete(TEMPLATE_KEY))).catch(() => {})

const granted = async (h) => (await h.queryPermission({ mode: 'readwrite' })) === 'granted'
// Re-asking needs a click (user activation), so only ever call this from a click handler.
const ask = async (h) => (await h.requestPermission({ mode: 'readwrite' })) === 'granted'

// ---- the lock ------------------------------------------------------------------------
const LOCK_KEY = 'ez-autosave-lock'
const readLocked = () => { try { return localStorage.getItem(LOCK_KEY) === 'on' } catch { return false } }

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
 * Saves finished downloads without being asked. Auto-save is switched on per link tab, and a
 * download can still have its own folder on its card:
 *  - Each link tab has a switch and a folder. Switching ON always asks for that tab's folder;
 *    switching OFF resets it. The lock keeps every link tab — open or new — on one setting; unlocked, each tab is its own and new ones start off.
 *  - A download's own card folder wins, else its link tab's folder.
 *  - With a folder, the file goes there the moment it is ready. Without one, a GRACE_SECONDS
 *    countdown hands it to the browser's Downloads folder.
 *  - While any folder picker is open, a finished file waits ('hold') and saves into the
 *    folder just chosen.
 *
 * Lives once, in App: the Downloads panel is mounted twice (desktop column and mobile
 * sheet), so doing this inside a card would save every file twice.
 */
export function useAutoSave({ pages, enabled, markSaved }) {
  const jobs = pages.flatMap((p) => p.jobs)
  const pageOf = useRef(new Map()) // job id -> its link tab's id
  pageOf.current = new Map(pages.flatMap((p) => p.jobs.map((j) => [j.id, p.id])))
  const [tabs, setTabs] = useState({}) // page id -> { on, folder }
  const tabsRef = useRef({})
  const [locked, setLockedState] = useState(readLocked)
  const lockedRef = useRef(locked)
  const template = useRef(null) // { on, folder } new tabs copy while locked
  const picking = useRef(0) // folder pickers open right now
  const held = useRef(new Set()) // finished jobs waiting for that picker to close
  const knownPages = useRef(null)
  const [folders, setFolders] = useState({}) // job id -> { handle, name, granted, key }
  const [ready, setReady] = useState(false) // the remembered folders have been looked up
  const [state, setState] = useState({}) // job id -> { phase: wait|hold|saving|saved|failed, ... }
  const timers = useRef(new Map()) // job id -> countdown timeout (exactly the jobs in 'wait')
  const seen = useRef(new Set()) // finished jobs already handed to begin()
  const knownJobs = useRef(new Set())
  const slots = useRef({ busy: 0, waiting: [] })
  const jobsRef = useRef(jobs)
  const pagesRef = useRef(pages)
  const foldersRef = useRef({})
  jobsRef.current = jobs
  pagesRef.current = pages

  const put = useCallback((id, s) => setState((m) => ({ ...m, [id]: s })), [])
  const stop = useCallback((id) => {
    clearTimeout(timers.current.get(id))
    timers.current.delete(id)
  }, [])
  const tabOn = useCallback((jobId) => !!tabsRef.current[pageOf.current.get(jobId)]?.on, [])

  const putTab = useCallback((pageId, t, persist = true) => {
    tabsRef.current = { ...tabsRef.current, [pageId]: t }
    setTabs(tabsRef.current)
    if (persist) storeTab(pageId, t)
  }, [])

  const dropTab = useCallback((pageId) => {
    const rest = { ...tabsRef.current }
    delete rest[pageId]
    tabsRef.current = rest
    setTabs(rest)
    storeTab(pageId, null)
  }, [])

  /** Give these link tabs one setting. Each keeps its own copy, filed under its own key. */
  const putTabs = useCallback((ids, t, persist = true) => {
    for (const id of ids) putTab(id, { on: t.on, folder: t.folder && { ...t.folder, key: 'tab:' + id } }, persist)
  }, [putTab])

  const storeTemplate = useCallback((t) => { template.current = t; storeTemplateRaw(t) }, [])

  const applyFolder = useCallback((jobId, f) => {
    const next = { ...foldersRef.current }
    if (f) next[jobId] = { ...f, key: 'folder:' + jobId }
    else delete next[jobId]
    foldersRef.current = next
    setFolders(next)
  }, [])

  /** This download's own folder, else its link tab's. */
  const folderFor = useCallback((jobId) => foldersRef.current[jobId] || tabsRef.current[pageOf.current.get(jobId)]?.folder || undefined, [])

  /** Record whether the browser still lets us write to this folder — wherever it lives now. */
  const regrant = useCallback((f, ok) => {
    if (f.key.startsWith('tab:')) {
      for (const [pageId, t] of Object.entries(tabsRef.current)) {
        if (t.folder?.handle === f.handle) putTab(pageId, { ...t, folder: { ...t.folder, granted: ok } })
      }
    } else {
      const jobId = f.key.slice('folder:'.length)
      const cur = foldersRef.current[jobId]
      if (cur?.handle === f.handle) applyFolder(jobId, { ...cur, granted: ok })
    }
  }, [applyFolder, putTab])

  /** Every folder picker goes through here, so a file that finishes meanwhile waits for the answer. */
  const choose = useCallback(async () => {
    picking.current++
    try {
      return await window.showDirectoryPicker({ id: 'ez-save', mode: 'readwrite', startIn: 'downloads' })
    } catch {
      return null // dismissed
    } finally {
      picking.current--
    }
  }, [])

  const hold = useCallback((job) => {
    stop(job.id)
    held.current.add(job.id)
    put(job.id, { phase: 'hold' })
  }, [put, stop])

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
        if (e.name === 'NotAllowedError' || e.name === 'SecurityError') regrant(dir, false) // the card says "needs access"
      }
    }
    if (dir) {
      put(job.id, { phase: 'saving', where, pct: 0, queued: slots.current.busy >= MAX_PARALLEL_SAVES })
      withSlot(task)
    } else {
      task() // the browser's own download is instant and keeps its own queue
    }
  }, [markSaved, put, regrant, stop, withSlot])

  // Every automatic save goes through here, so another tab that got there first wins.
  // (Pressing Save file is a decision, not an automation: it always goes ahead.)
  const auto = useCallback((job, dir) => {
    if (!savedElsewhere(job.id)) return run(job, dir)
    stop(job.id) // another tab is already saving this one
    put(job.id, { phase: 'elsewhere' })
  }, [put, run, stop])

  const begin = useCallback(async (job) => {
    if (picking.current) return hold(job) // a folder is being chosen right now: wait for it
    const f = folderFor(job.id)
    if (f) {
      const ok = await granted(f.handle).catch(() => false)
      if (picking.current) return hold(job)
      if (ok) return auto(job, f)
      // The browser has taken the permission back (it does that between visits).
      if (f.granted) regrant(f, false)
    }
    put(job.id, { phase: 'wait', deadline: Date.now() + GRACE_SECONDS * 1000 })
    timers.current.set(job.id, setTimeout(() => (picking.current ? hold(job) : auto(job, null)), GRACE_SECONDS * 1000))
  }, [auto, folderFor, hold, put, regrant])

  /** The picker closed: files that finished meanwhile now save — into the folder just chosen. */
  const releaseHeld = useCallback(() => {
    if (picking.current) return
    const ids = [...held.current]
    held.current.clear()
    for (const id of ids) {
      const job = jobsRef.current.find((j) => j.id === id)
      if (job && job.status === 'COMPLETED' && !job.saved && tabOn(id)) begin(job)
      else { // gone, saved, or its tab switched off: let the main effect start it afresh later
        seen.current.delete(id)
        setState((m) => { const rest = { ...m }; delete rest[id]; return rest })
      }
    }
  }, [begin, tabOn])

  // A file already counting down goes straight to the folder it was waiting for. It must
  // be the EFFECTIVE folder: a card with no choice of its own still inherits Auto-save's,
  // and reading only the card's own entry handed those files to the browser instead.
  const releaseIfWaiting = useCallback((jobId) => {
    if (!timers.current.has(jobId)) return
    const job = jobsRef.current.find((j) => j.id === jobId)
    if (job) auto(job, folderFor(jobId))
  }, [auto, folderFor])

  /**
   * A link tab's switch — every tab's while the lock is closed. ON always asks for the folder, the
   * only way a page can write a file without a dialog. Dismissing the picker still leaves it on,
   * falling back to the browser.
   */
  const setOn = useCallback(async (pageId, value) => {
    const ids = lockedRef.current ? pagesRef.current.map((p) => p.id) : [pageId]
    let t = OFF
    if (value) {
      putTabs(ids, { on: true, folder: null }, false) // on now, so files that finish meanwhile wait for the folder
      const handle = canPickFolder ? await choose() : null
      t = { on: true, folder: handle && { handle, name: handle.name, granted: true } }
    }
    putTabs(ids, t)
    if (lockedRef.current) storeTemplate(t)
    releaseHeld()
  }, [choose, putTabs, releaseHeld, storeTemplate])

  /** The lock beside the switch: closed, every link tab — open or new — follows this tab's setting. */
  const setLocked = useCallback((pageId, value) => {
    lockedRef.current = value
    setLockedState(value)
    try { localStorage.setItem(LOCK_KEY, value ? 'on' : 'off') } catch { /* private window: just not remembered */ }
    if (!value) return storeTemplate(null) // every tab keeps what it has; new ones start off
    const t = tabsRef.current[pageId] || OFF
    putTabs(pagesRef.current.map((p) => p.id), t)
    storeTemplate(t)
  }, [putTabs, storeTemplate])

  /** Choose the folder for this one download's card. A link tab's folder is set by its switch. */
  const pick = useCallback(async (jobId) => {
    const handle = await choose()
    if (handle) {
      storeFolder(jobId, handle)
      applyFolder(jobId, { handle, name: handle.name, granted: true }) // choosing it is itself the grant
      releaseIfWaiting(jobId)
    }
    releaseHeld()
  }, [applyFolder, choose, releaseHeld, releaseIfWaiting])

  /** Re-grant a remembered folder after the browser has forgotten the permission. */
  const allow = useCallback(async (jobId) => {
    const f = folderFor(jobId)
    if (!f) return
    let ok = false
    try { ok = await ask(f.handle) } catch { /* refused */ }
    regrant(f, ok)
    if (ok) releaseIfWaiting(jobId)
  }, [folderFor, regrant, releaseIfWaiting])

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

  // Restore every link tab's switch and folder, the lock's template, and each download's own
  // folder; sweep whatever no longer belongs to a tab or a download.
  useEffect(() => {
    let live = true
    ;(async () => {
      if (canPickFolder) {
        const presentJobs = new Set(jobsRef.current.map((j) => j.id))
        const presentPages = new Set(pagesRef.current.map((p) => p.id))
        const restore = async (h, key) => h && h.name ? { handle: h, name: h.name, granted: await granted(h).catch(() => false), key } : null
        for (const [key, val] of await loadFolders()) {
          if (key === TEMPLATE_KEY) {
            if (val && readLocked()) template.current = { on: !!val.on, folder: await restore(val.handle, TEMPLATE_KEY) }
            else forgetKey(key)
          } else if (key.startsWith('tab:')) {
            const pageId = key.slice(4)
            if (!val || !presentPages.has(pageId)) { forgetKey(key); continue }
            if (live) putTab(pageId, { on: !!val.on, folder: await restore(val.handle, key) }, false)
          } else {
            const id = key.startsWith('folder:') ? key.slice('folder:'.length) : null
            if (!val || !val.name || !id || !presentJobs.has(id)) { forgetKey(key); continue }
            if (live) applyFolder(id, { handle: val, name: val.name, granted: await granted(val).catch(() => false) })
          }
        }
      }
      if (live) setReady(true)
    })()
    return () => { live = false }
  }, [applyFolder, putTab])

  // A new link tab follows the lock; a closed one takes its setting with it.
  const pageIds = pages.map((p) => p.id).join(' ')
  useEffect(() => {
    const ids = new Set(pageIds ? pageIds.split(' ') : [])
    if (knownPages.current) {
      const t = template.current
      for (const id of ids) {
        if (!knownPages.current.has(id) && lockedRef.current && t) {
          putTabs([id], t)
        }
      }
      for (const id of knownPages.current) if (!ids.has(id)) dropTab(id)
    }
    knownPages.current = ids
  }, [pageIds, putTabs, dropTab])

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
    // and every one in a tab whose switch went off.
    const finished = new Set(jobs.filter((j) => j.status === 'COMPLETED').map((j) => j.id))
    for (const id of [...timers.current.keys()]) {
      const on = enabled && tabOn(id)
      if (on && finished.has(id)) continue
      stop(id)
      if (!on) { // forget it entirely, so switching back on starts the countdown afresh
        seen.current.delete(id)
        setState((m) => { const rest = { ...m }; delete rest[id]; return rest })
      }
    }
    if (!enabled || !ready) return
    for (const job of jobs) {
      if (job.status === 'COMPLETED') {
        if (tabOn(job.id) && !job.saved && !seen.current.has(job.id)) {
          seen.current.add(job.id)
          begin(job)
        }
      } else if (seen.current.delete(job.id)) { // retried: its next completion saves again
        forgetSaved(job.id)
        setState((m) => { const rest = { ...m }; delete rest[job.id]; return rest })
      }
    }
  }, [jobs, tabs, enabled, ready, begin, stop, tabOn])

  return {
    canPick: canPickFolder,
    state,
    folders,
    folderFor,
    /** This link tab's switch and folder. */
    tab: (pageId) => tabs[pageId] || OFF,
    /** Is Auto-save on for the tab this download belongs to? */
    isOn: (jobId) => !!tabs[pageOf.current.get(jobId)]?.on,
    /** The folder this download falls back to when its own choice is cleared. */
    tabFolderFor: (jobId) => tabs[pageOf.current.get(jobId)]?.folder || null,
    setOn,
    locked,
    setLocked,
    pick,
    allow,
    clear,
    saved,
  }
}
