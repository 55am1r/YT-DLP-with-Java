import { useState } from 'react'
import { analyze } from '../api'
import { fmtDuration } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']
const CONTAINERS = ['mp4', 'mkv', 'webm']

/**
 * Playlist view with two modes:
 *  - "Download all" → one zip job using the shared settings shown up top.
 *  - Multi-select   → the shared settings disappear and every ticked item carries its
 *                     OWN video/audio choice, quality and container, queueing as its
 *                     own job. One playlist can yield some MP4s and some MP3s.
 * Selections and per-item settings persist when toggling modes — hidden, not lost.
 */
export default function PlaylistPanel({ analysis, onStart }) {
  const audioOnly = analysis.music
  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [container, setContainer] = useState('mp4')
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [all, setAll] = useState(true)
  const [selected, setSelected] = useState(() => new Set())
  const [probes, setProbes] = useState({})
  const [perItem, setPerItem] = useState({})

  const items = analysis.items || []
  const count = analysis.playlistCount || items.length
  const chosen = [...selected].sort((a, b) => a - b)
  const sharedAudio = audioOnly || mode === 'audio'
  const sharedLabel = sharedAudio ? audioFormat.toUpperCase() : `${height}p · ${container.toUpperCase()}`

  const defaults = () => ({ kind: audioOnly ? 'audio' : mode, height, container, audioFormat })
  const cfgOf = (index) => perItem[index] || defaults()

  /** Probe an entry so we can offer the resolutions it actually has. */
  async function probe(item) {
    if (probes[item.index]?.formats || probes[item.index]?.loading || !item.url) return
    setProbes((p) => ({ ...p, [item.index]: { loading: true } }))
    try {
      const info = await analyze(item.url)
      const formats = info.videoFormats || []
      setProbes((p) => ({ ...p, [item.index]: { loading: false, formats } }))
      setPerItem((p) => {
        const cur = p[item.index] || defaults()
        const stillValid = formats.some((f) => f.height === cur.height)
        return { ...p, [item.index]: { ...cur, height: stillValid ? cur.height : (formats[0]?.height ?? cur.height) } }
      })
    } catch (e) {
      setProbes((p) => ({ ...p, [item.index]: { loading: false, error: e.message || 'Could not read formats' } }))
    }
  }

  function toggle(item) {
    const on = selected.has(item.index)
    const next = new Set(selected)
    if (on) next.delete(item.index)
    else next.add(item.index)
    setSelected(next)
    if (!on) {
      setPerItem((p) => (p[item.index] ? p : { ...p, [item.index]: defaults() }))
      probe(item)
    }
  }

  function setItemField(index, field, value) {
    setPerItem((p) => ({ ...p, [index]: { ...(p[index] || defaults()), [field]: value } }))
  }

  function submit() {
    if (all) {
      onStart({
        url: analysis.url,
        kind: sharedAudio ? 'audio' : 'video',
        height: sharedAudio ? null : height,
        container: sharedAudio ? null : container,
        audioFormat: sharedAudio ? audioFormat : null,
        playlist: true,
        title: analysis.title,
      })
      return
    }
    chosen.forEach((idx) => {
      const item = items.find((i) => i.index === idx)
      if (!item?.url) return
      const cfg = cfgOf(idx)
      const itemAudio = audioOnly || cfg.kind === 'audio'
      onStart({
        url: item.url,
        kind: itemAudio ? 'audio' : 'video',
        height: itemAudio ? null : cfg.height,
        container: itemAudio ? null : cfg.container,
        audioFormat: itemAudio ? cfg.audioFormat : null,
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

      {/* Shared settings exist only in "download all" mode — in multi-select each item
          carries its own type, quality and container. */}
      {all && (
        <>
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

          {sharedAudio ? (
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
              ? `Only ${chosen.length} selected ${chosen.length === 1 ? 'video' : 'videos'} will be downloaded — each with its own settings`
              : 'Tick the videos you want, then set each one individually'}
          </p>
          <div className="pl-list">
            {items.map((it) => {
              const on = selected.has(it.index)
              const pr = probes[it.index] || {}
              const cfg = cfgOf(it.index)
              const itemAudio = audioOnly || cfg.kind === 'audio'
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

                    {!on && (
                      <div className="pl-chips">
                        <span className="pl-hint">
                          <i className="fa-regular fa-circle-question" /> Select to choose type &amp; quality
                        </span>
                      </div>
                    )}

                    {on && (
                      <>
                        {!audioOnly && (
                          <div className="pl-chips">
                            <button
                              className={`pill sm ${cfg.kind === 'video' ? 'active' : ''}`}
                              onClick={() => setItemField(it.index, 'kind', 'video')}
                            >
                              <i className="fa-solid fa-film" /> Video
                            </button>
                            <button
                              className={`pill sm ${cfg.kind === 'audio' ? 'active' : ''}`}
                              onClick={() => setItemField(it.index, 'kind', 'audio')}
                            >
                              <i className="fa-solid fa-music" /> Audio
                            </button>
                          </div>
                        )}

                        {itemAudio ? (
                          <div className="pl-chips">
                            {AUDIO_FORMATS.map((a) => (
                              <button
                                key={a}
                                className={`pill sm ${cfg.audioFormat === a ? 'active' : ''}`}
                                onClick={() => setItemField(it.index, 'audioFormat', a)}
                              >
                                {a.toUpperCase()}
                              </button>
                            ))}
                          </div>
                        ) : pr.loading ? (
                          <div className="pl-chips muted small">
                            <i className="fa-solid fa-circle-notch fa-spin" /> reading available qualities…
                          </div>
                        ) : pr.error ? (
                          <div className="pl-chips small" style={{ color: 'var(--accent-2)' }}>{pr.error}</div>
                        ) : pr.formats ? (
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
                        ) : null}
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
        {all ? `Download all ${count} · ${sharedLabel}` : `Download ${chosen.length || ''} selected`}
      </button>
    </section>
  )
}
