import { useState } from 'react'
import { fmtDuration } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']
const CONTAINERS = ['mp4', 'mkv', 'webm']

/**
 * Playlist view: the shared format settings on top, then every item listed with its
 * own thumbnail. Per-item chips are info-only; picking items is done with the
 * checkboxes. Selections survive toggling back to "download all" (kept in state,
 * just hidden), exactly as the spec asks.
 */
export default function PlaylistPanel({ analysis, onStart }) {
  const audioOnly = analysis.music
  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [container, setContainer] = useState('mp4')
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [all, setAll] = useState(true)
  const [selected, setSelected] = useState(() => new Set())

  const isAudio = audioOnly || mode === 'audio'
  const items = analysis.items || []
  const count = analysis.playlistCount || items.length
  const chosen = [...selected].sort((a, b) => a - b)

  function toggle(index) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })
  }

  function submit() {
    onStart({
      url: analysis.url,
      kind: isAudio ? 'audio' : 'video',
      height: isAudio ? null : height,
      container: isAudio ? null : container,
      audioFormat: isAudio ? audioFormat : null,
      playlist: true,
      title: analysis.title,
      items: all ? null : chosen,
    })
  }

  const formatLabel = isAudio ? audioFormat.toUpperCase() : `${height}p · ${container.toUpperCase()}`
  const canDownload = all || chosen.length > 0

  return (
    <section className="panel glass">
      <div className="media-head" style={{ display: 'flex', gap: 14, alignItems: 'center', paddingTop: 0 }}>
        {analysis.thumbnail && <img className="pl-thumb" src={analysis.thumbnail} alt="" />}
        <div style={{ minWidth: 0 }}>
          <h2 className="title">{analysis.title || 'Playlist'}</h2>
          <p className="muted">
            {analysis.uploader} · Playlist · {count} {count === 1 ? 'video' : 'videos'}
            {audioOnly && <span className="tag">music</span>}
          </p>
        </div>
      </div>

      {!audioOnly && (
        <div className="seg glass">
          <button className={`seg-btn ${mode === 'video' ? 'active' : ''}`} onClick={() => setMode('video')}>🎬 Video</button>
          <button className={`seg-btn ${mode === 'audio' ? 'active' : ''}`} onClick={() => setMode('audio')}>♪ Audio</button>
        </div>
      )}

      {isAudio ? (
        <div className="field">
          <label>Audio format — applies to every item</label>
          <div className="pills">
            {AUDIO_FORMATS.map((a) => (
              <button key={a} className={`pill ${audioFormat === a ? 'active' : ''}`} onClick={() => setAudioFormat(a)}>
                {a.toUpperCase()}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <>
          <div className="field">
            <label>Quality — applies to every item</label>
            <div className="pills">
              {analysis.videoFormats.map((f) => (
                <button key={f.height} className={`pill ${height === f.height ? 'active' : ''}`} onClick={() => setHeight(f.height)}>
                  {f.label}
                </button>
              ))}
            </div>
          </div>
          <div className="field">
            <label>Container</label>
            <div className="pills">
              {CONTAINERS.map((c) => (
                <button key={c} className={`pill ${container === c ? 'active' : ''}`} onClick={() => setContainer(c)}>
                  {c.toUpperCase()}
                </button>
              ))}
            </div>
          </div>
        </>
      )}

      <label className="checkbox">
        <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
        <span>Download all {count} {count === 1 ? 'item' : 'items'} as a .zip</span>
      </label>

      {!all && (
        <>
          <p className="pl-selnote">
            {chosen.length > 0
              ? `Only ${chosen.length} selected ${chosen.length === 1 ? 'video' : 'videos'} will be downloaded`
              : 'Select the videos you want to download'}
          </p>
          <div className="pl-list">
            {items.map((it) => (
              <div key={it.index} className={`pl-item glass ${selected.has(it.index) ? 'selected' : ''}`}>
                <input
                  className="pl-check"
                  type="checkbox"
                  checked={selected.has(it.index)}
                  onChange={() => toggle(it.index)}
                  aria-label={`Select ${it.title}`}
                />
                {it.thumbnail && <img className="pl-thumb" src={it.thumbnail} alt="" loading="lazy" />}
                <div className="pl-meta">
                  <p className="pl-title">{it.index}. {it.title}</p>
                  <span className="muted small">{it.durationSeconds != null ? fmtDuration(it.durationSeconds) : '—'}</span>
                  <div className="pl-chips">
                    <span className="pill info">{formatLabel}</span>
                  </div>
                </div>
              </div>
            ))}
            {items.length === 0 && <p className="muted small">This playlist didn't expose its items.</p>}
          </div>
        </>
      )}

      <button className="btn btn-primary btn-lg" onClick={submit} disabled={!canDownload}>
        {all ? `Download all ${count} · ${formatLabel}` : `Download ${chosen.length || ''} selected · ${formatLabel}`}
      </button>
    </section>
  )
}
