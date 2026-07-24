import { useState } from 'react'
import { analyze } from '../api'
import { fmtDuration } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']
const CONTAINERS = ['mp4', 'mkv', 'webm']

/**
 * Playlist view.
 *  - "Download all"  → one zip job using the shared settings.
 *  - Multi-select    → each ticked item probes ITS OWN available resolutions and gets
 *                      its own quality/container, then queues as its own job.
 * Selections and per-item settings survive toggling back to "Download all" — kept in
 * state, just hidden, as the spec asks.
 */
export default function PlaylistPanel({ analysis, onStart }) {
  const audioOnly = analysis.music
  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [container, setContainer] = useState('mp4')
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [all, setAll] = useState(true)
  const [selected, setSelected] = useState(() => new Set())
  const [probes, setProbes] = useState({})   // index -> {loading, formats, error}
  const [perItem, setPerItem] = useState({}) // index -> {height, container}

  const isAudio = audioOnly || mode === 'audio'
  const items = analysis.items || []
  const count = analysis.playlistCount || items.length
  const chosen = [...selected].sort((a, b) => a - b)
  const sharedLabel = isAudio ? audioFormat.toUpperCase() : `${height}p · ${container.toUpperCase()}`

  /** Probe one entry so we can offer the resolutions it actually has. */
  async function probe(item) {
    if (probes[item.index]?.formats || probes[item.index]?.loading || !item.url) return
    setProbes((p) => ({ ...p, [item.index]: { loading: true } }))
    try {
      const info = await analyze(item.url)
      const formats = info.videoFormats || []
      setProbes((p) => ({ ...p, [item.index]: { loading: false, formats } }))
      setPerItem((p) => ({
        ...p,
        [item.index]: p[item.index] || { height: formats[0]?.height ?? height, container },
      }))
    } catch (e) {
      setProbes((p) => ({ ...p, [item.index]: { loading: false, error: e.message || 'Could not read formats' } }))
    }
  }

  function toggle(item) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(item.index)) next.delete(item.index)
      else {
        next.add(item.index)
        probe(item)
      }
      return next
    })
  }

  function setItemField(index, field, value) {
    setPerItem((p) => ({ ...p, [index]: { ...(p[index] || { height, container }), [field]: value } }))
  }

  function submit() {
    if (all) {
      onStart({
        url: analysis.url,
        kind: isAudio ? 'audio' : 'video',
        height: isAudio ? null : height,
        container: isAudio ? null : container,
        audioFormat: isAudio ? audioFormat : null,
        playlist: true,
        title: analysis.title,
      })
      return
    }
    // One job per item, each with its own quality/container.
    chosen.forEach((idx) => {
      const item = items.find((i) => i.index === idx)
      if (!item?.url) return
      const cfg = perItem[idx] || { height, container }
      onStart({
        url: item.url,
        kind: isAudio ? 'audio' : 'video',
        height: isAudio ? null : cfg.height,
        container: isAudio ? null : cfg.container,
        audioFormat: isAudio ? audioFormat : null,
        playlist: false,
        title: item.title,
      })
    })
  }

  return (
    <section className="panel glass">
      <div className="pl-header">
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
          <button className={`seg-btn ${mode === 'video' ? 'active' : ''}`} onClick={() => setMode('video')}>
            <i className="fa-solid fa-film" /> Video
          </button>
          <button className={`seg-btn ${mode === 'audio' ? 'active' : ''}`} onClick={() => setMode('audio')}>
            <i className="fa-solid fa-music" /> Audio
          </button>
        </div>
      )}

      {isAudio ? (
        <div className="field">
          <label>Audio format{all ? ' — applies to every item' : ''}</label>
          <div className="pills">
            {AUDIO_FORMATS.map((a) => (
              <button key={a} className={`pill ${audioFormat === a ? 'active' : ''}`} onClick={() => setAudioFormat(a)}>
                {a.toUpperCase()}
              </button>
            ))}
          </div>
        </div>
      ) : (
        all && (
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
        )
      )}

      <label className="checkbox">
        <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
        <span>Download all {count} {count === 1 ? 'item' : 'items'} as a .zip</span>
      </label>

      {!all && (
        <>
          <p className="pl-selnote">
            {chosen.length > 0
              ? `Only ${chosen.length} selected ${chosen.length === 1 ? 'video' : 'videos'} will be downloaded — each with its own quality`
              : 'Tick the videos you want, then pick a quality for each'}
          </p>
          <div className="pl-list">
            {items.map((it) => {
              const on = selected.has(it.index)
              const pr = probes[it.index] || {}
              const cfg = perItem[it.index] || { height, container }
              return (
                <div key={it.index} className={`pl-item glass ${on ? 'selected' : ''}`}>
                  <input
                    className="pl-check"
                    type="checkbox"
                    checked={on}
                    onChange={() => toggle(it)}
                    aria-label={`Select ${it.title}`}
                  />
                  {it.thumbnail && <img className="pl-thumb" src={it.thumbnail} alt="" loading="lazy" />}
                  <div className="pl-meta">
                    <p className="pl-title">{it.index}. {it.title}</p>
                    <span className="muted small">{it.durationSeconds != null ? fmtDuration(it.durationSeconds) : '—'}</span>

                    {!on && <div className="pl-chips"><span className="pill info">{sharedLabel}</span></div>}

                    {on && isAudio && <div className="pl-chips"><span className="pill info">{audioFormat.toUpperCase()}</span></div>}

                    {on && !isAudio && pr.loading && (
                      <div className="pl-chips muted small">
                        <i className="fa-solid fa-circle-notch fa-spin" /> reading available qualities…
                      </div>
                    )}

                    {on && !isAudio && pr.error && (
                      <div className="pl-chips small" style={{ color: 'var(--accent-2)' }}>{pr.error}</div>
                    )}

                    {on && !isAudio && pr.formats && (
                      <>
                        <div className="pl-chips">
                          {pr.formats.map((f) => (
                            <button
                              key={f.height}
                              className={`pill sm ${cfg.height === f.height ? 'active' : ''}`}
                              onClick={() => setItemField(it.index, 'height', f.height)}
                            >
                              {f.label}
                            </button>
                          ))}
                        </div>
                        <div className="pl-chips">
                          {CONTAINERS.map((c) => (
                            <button
                              key={c}
                              className={`pill sm ${cfg.container === c ? 'active' : ''}`}
                              onClick={() => setItemField(it.index, 'container', c)}
                            >
                              {c.toUpperCase()}
                            </button>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                </div>
              )
            })}
            {items.length === 0 && <p className="muted small">This playlist didn't expose its items.</p>}
          </div>
        </>
      )}

      <button className="btn btn-primary btn-lg" onClick={submit} disabled={!all && chosen.length === 0}>
        <i className="fa-solid fa-download" />
        {all
          ? `Download all ${count} · ${sharedLabel}`
          : `Download ${chosen.length || ''} selected${isAudio ? ` · ${audioFormat.toUpperCase()}` : ''}`}
      </button>
    </section>
  )
}
