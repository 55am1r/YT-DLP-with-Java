import { useEffect, useRef, useState } from 'react'
import JobCard from './JobCard'
import { GRACE_SECONDS } from '../autosave'

/**
 * Is there more list below what's showing? True only while the cards overflow their box and
 * the last one isn't in view yet — that's when the bottom fades out.
 */
function useMoreBelow(ref) {
  const [more, setMore] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el) return undefined
    const check = () => setMore(el.scrollHeight - el.clientHeight - el.scrollTop > 4)
    check()
    el.addEventListener('scroll', check, { passive: true })
    const ro = new ResizeObserver(check)
    ro.observe(el)
    const mo = new MutationObserver(check) // cards added, removed, or growing as they progress
    mo.observe(el, { childList: true, subtree: true })
    return () => { el.removeEventListener('scroll', check); ro.disconnect(); mo.disconnect() }
  }, [ref])
  return more
}

/**
 * The Auto-save switch for THIS link, beside Clear. On: every finished download in the link
 * saves itself — into the folder chosen on its own card, or the link's folder, or the browser's
 * Downloads folder after a short countdown. Off: nothing in the link saves by itself.
 */
function AutoSaveSwitch({ on, busy, noFolder, locked, onToggle }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      // Locked while a file is being written: flipping it mid-write would leave a
      // half-saved file on disk with nothing watching it finish.
      disabled={busy}
      className={`switch${on ? ' on' : ''}`}
      onClick={onToggle}
      title={busy
        ? 'Saving right now — you can change this once it finishes.'
        : on
          ? `Auto-save is on for ${locked ? 'every link' : 'this link'}. Each finished download is written straight into its folder.${noFolder ? ' No folder is set, so files are handed to your browser instead.' : ''}`
          : `Auto-save is off for ${locked ? 'every link' : 'this link'}. ${noFolder ? 'Turning it on asks for a folder once; after that it is remembered.' : 'Turning it on saves into the folder you chose before.'}`}
    >
      <span className="switch-track"><span className="switch-knob" /></span>
      <span>Auto-save</span>
    </button>
  )
}

/**
 * The downloads for ONE link. Always shown — even with nothing in it — so the section
 * and its (disabled) Clear button stay put and a placeholder explains the empty state.
 * Clearing here only wipes this link's files on the server; other tabs keep theirs.
 */
export default function DownloadsPanel({ pageId, jobs, onClear, onExpired, onRetry, autosave, clearing }) {
  const clearable = jobs.some((j) => ['COMPLETED', 'FAILED', 'CANCELED'].includes(j.status))
  const t = autosave.tab(pageId) // this link's Auto-save switch and folder
  const busy = jobs.some((j) => autosave.state[j.id]?.phase === 'saving')
  const locked = autosave.locked
  const listRef = useRef(null)
  const moreBelow = useMoreBelow(listRef)

  return (
    <section className="jobs">
      <div className="dl-head">
        <h2 className="section-title">Downloads</h2>
        <div className="dl-head-actions">
          <span className="autosave-ctl">
            <AutoSaveSwitch
              on={t.on}
              busy={busy}
              noFolder={!t.folder}
              locked={locked}
              onToggle={() => autosave.setOn(pageId, !t.on)}
            />
            {/* Locked, every link — open or new — shares this Auto-save setting and folder. */}
            <button
              type="button"
              className={`lock-btn${locked ? ' on' : ''}`}
              aria-pressed={locked}
              aria-label={locked ? 'Unlock Auto-save (every link follows this one)' : 'Lock Auto-save for every link'}
              title={locked
                ? 'Locked: every link uses this Auto-save setting and folder, new ones too. Click to unlock.'
                : 'Unlocked: each link has its own Auto-save, and new links start with it off. Click to use this setting for every link.'}
              onClick={() => autosave.setLocked(pageId, !autosave.locked)}
            >
              <i className={`fa-solid ${locked ? 'fa-lock' : 'fa-lock-open'}`} />
            </button>
          </span>
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
      </div>

      {/* Without a folder nothing can be written silently — the browser's own downloader
          takes over, and it may ask where to put each file. Say so once, here, rather than
          on every card. */}
      {t.on && !autosave.canPick && (
        <p className="dl-hint">
          Finished files go to your browser {GRACE_SECONDS}s after they finish, and it may ask where to save each one.
          {window.isSecureContext ? ' Saving without a prompt needs Chrome or Edge.' : ' Saving without a prompt needs the secure (https) link.'}
        </p>
      )}
      {t.on && autosave.canPick && !t.folder && (
        <p className="dl-hint">
          <i className="fa-solid fa-triangle-exclamation" /> No folder chosen, so finished files are handed to your
          browser — which may ask where to save each one. Choose a folder on any download below to save silently.
        </p>
      )}

      {/* The cards scroll on their own beside the main panel, so the head above stays put. */}
      <div className={`jobs-list${moreBelow ? ' more-below' : ''}`} ref={listRef}>
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
            <JobCard key={j.id} job={j} onExpired={onExpired} onRetry={onRetry} autosave={autosave} />
          ))
        )}
      </div>
    </section>
  )
}
