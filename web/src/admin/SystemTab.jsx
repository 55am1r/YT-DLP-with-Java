import { useState } from 'react'
import { postAnnouncement } from './adminApi'
import { Chip } from './AdminBits'
import { bytes, fmtDateTime, fmtDuration } from './format'

/** The Mac itself: is a restart safe, the links, yt-dlp, disk — and the team announcement. */
export default function SystemTab({ data, onChanged }) {
  const s = data.system
  const used = s.diskTotalBytes && s.diskFreeBytes != null ? s.diskTotalBytes - s.diskFreeBytes : null
  const usedPct = used != null ? Math.round((used / s.diskTotalBytes) * 100) : null

  return (
    <>
      <section className={`panel glass restart ${s.safeToRestart ? 'is-safe' : 'is-busy'}`}>
        <Chip tone={s.safeToRestart ? 'good' : 'warning'} icon={s.safeToRestart ? 'fa-circle-check' : 'fa-triangle-exclamation'}>
          {s.safeToRestart ? 'Safe to restart' : 'Not a good moment to restart'}
        </Chip>
        <p>{s.restartNote}</p>
        <p className="muted small">
          A restart or redeploy deletes every download in progress and every finished file still waiting on the server.
        </p>
      </section>

      <div className="admin-cols">
        <section className="panel glass">
          <div className="panel-head"><h2>Links</h2></div>
          <dl className="facts">
            <dt>Public link</dt>
            <dd>{s.publicUrl ? <CopyLink url={s.publicUrl} /> : <span className="muted">No tunnel address found</span>}</dd>
            <dt>Office LAN</dt>
            <dd>{s.lanUrl ? <CopyLink url={s.lanUrl} /> : <span className="muted">Unknown</span>}</dd>
          </dl>
          <p className="muted small">The public link changes whenever the tunnel restarts; the redirect page follows it automatically.</p>
        </section>

        <section className="panel glass">
          <div className="panel-head"><h2>Server</h2></div>
          <dl className="facts">
            <dt>yt-dlp</dt>
            <dd>
              {s.ytdlpInstalled || 'unknown'}{' '}
              {s.ytdlpInstalled && (s.ytdlpUpToDate
                ? <Chip tone="good" icon="fa-circle-check">Latest</Chip>
                : <Chip tone="warning" icon="fa-arrow-up">{s.ytdlpLatest ? `${s.ytdlpLatest} available` : 'Update check pending'}</Chip>)}
            </dd>
            <dt>Running for</dt>
            <dd>{fmtDuration(s.uptimeMs)}</dd>
            <dt>Downloads</dt>
            <dd>{s.runningJobs} running · {s.unsavedFiles} finished but not saved by anyone yet</dd>
            <dt>Server clock</dt>
            <dd>{fmtDateTime(s.serverTime)} <span className="muted">({s.serverZone})</span></dd>
          </dl>
        </section>
      </div>

      <div className="admin-cols">
        <section className="panel glass">
          <div className="panel-head"><h2>Download disk</h2></div>
          {usedPct != null ? (
            <>
              <div className="meter big" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={usedPct}
                aria-label="Disk space used">
                <span style={{ width: `${usedPct}%` }} className={usedPct > 90 ? 'critical' : usedPct > 75 ? 'warning' : ''} />
              </div>
              <p><strong>{bytes(s.diskFreeBytes)}</strong> free of {bytes(s.diskTotalBytes)} <span className="muted">({usedPct}% used)</span></p>
            </>
          ) : <p className="muted">The download volume isn’t available.</p>}
        </section>

        <section className="panel glass">
          <div className="panel-head"><h2>Records</h2></div>
          <dl className="facts">
            <dt>Kept for</dt>
            <dd>{s.retentionDays} days, then deleted</dd>
            <dt>Stored in</dt>
            <dd className="mono small">{s.dataDir}</dd>
          </dl>
          <p className="muted small">Locations come from ipwho.is (or ipinfo.io), cached for 14 days.</p>
        </section>
      </div>

      <AnnouncementEditor current={data.announcement} onChanged={onChanged} />
    </>
  )
}

function CopyLink({ url }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      window.prompt('Copy this link:', url) // clipboard needs HTTPS; the LAN page is plain HTTP
    }
  }
  return (
    <span className="copy-link">
      <a href={url} target="_blank" rel="noopener noreferrer" className="mono">{url}</a>
      <button type="button" className="link-btn small" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
    </span>
  )
}

function AnnouncementEditor({ current, onChanged }) {
  const [text, setText] = useState(current?.text || '')
  const [level, setLevel] = useState(current?.level || 'info')
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState(null)

  async function save(nextText) {
    setBusy(true)
    setNote(null)
    try {
      await postAnnouncement(nextText, level)
      if (!nextText) setText('')
      setNote(nextText ? 'Posted — everyone sees it within a minute.' : 'Cleared.')
      onChanged()
    } catch (e) {
      setNote(e.message || 'Could not save it')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel glass">
      <div className="panel-head">
        <h2>Announcement to the team</h2>
        {current && <Chip tone={current.level === 'warn' ? 'warning' : 'info'} icon="fa-bullhorn">Showing now</Chip>}
      </div>
      <p className="muted small">A banner at the top of the downloader — for example before a restart.</p>
      <textarea
        className="input textarea" rows={3} maxLength={300} value={text} placeholder="e.g. The server restarts at 6 pm — finish your downloads before then."
        onChange={(e) => setText(e.target.value)} aria-label="Announcement text"
      />
      <div className="inline-form">
        <div className="seg seg-sm glass" role="group" aria-label="Style">
          <button type="button" className={`seg-btn ${level === 'info' ? 'active' : ''}`} aria-pressed={level === 'info'} onClick={() => setLevel('info')}>Info</button>
          <button type="button" className={`seg-btn ${level === 'warn' ? 'active' : ''}`} aria-pressed={level === 'warn'} onClick={() => setLevel('warn')}>Warning</button>
        </div>
        <span className="muted small">{text.length}/300</span>
        <span className="toolbar-end">
          {current && <button type="button" className="btn btn-sm" disabled={busy} onClick={() => save('')}>Clear</button>}
          <button type="button" className="btn btn-sm btn-primary" disabled={busy || !text.trim()} onClick={() => save(text.trim())}>
            <i className="fa-solid fa-bullhorn" /> Post
          </button>
        </span>
      </div>
      {note && <p className="muted small">{note}</p>}
    </section>
  )
}
