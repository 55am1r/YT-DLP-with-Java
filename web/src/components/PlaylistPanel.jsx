import { useEffect, useState } from 'react'
import { analyze, getPlaylistFormats } from '../api'
import FormatPicker, { defaultFormat } from './FormatPicker'
import { fmtDuration } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']

/**
 * Playlist view with two modes:
 *  - "Download all" → one zip job. Offered ONLY when every item exposes the same
 *    resolutions, and then only at those shared resolutions; one quality setting across
 *    items that don't share it would silently hand people different files.
 *  - Multi-select   → each ticked item carries its own type, format and quality.
 */
export default function PlaylistPanel({ analysis, onStart, codecs }) {
  const audioOnly = analysis.music
  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(null)
  const [format, setFormat] = useState(defaultFormat)
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [all, setAll] = useState(true)
  const [selected, setSelected] = useState(() => new Set())
  const [probes, setProbes] = useState({})
  const [perItem, setPerItem] = useState({})

  // Can this playlist honestly be zipped at one setting? The server reads every item to
  // find out, so the verdict arrives after the page does.
  const [uniformity, setUniformity] = useState({ loading: true })

  const items = analysis.items || []
  const count = analysis.playlistCount || items.length
  const chosen = [...selected].sort((a, b) => a - b)
  const sharedAudio = audioOnly || mode === 'audio'
  const zipOk = uniformity.uniform === true
  const zipMode = all && zipOk

  useEffect(() => {
    let dead = false
    setUniformity({ loading: true })
    getPlaylistFormats(analysis.url)
      .then((r) => {
        if (dead) return
        setUniformity({ loading: false, ...r })
        if (!r.uniform) setAll(false) // no honest single zip — go straight to per-item
        else if (r.common?.length) setHeight((h) => h ?? r.common[0].height)
      })
      .catch((e) => {
        if (dead) return
        setUniformity({ loading: false, uniform: false, reason: e.message || 'Could not read this playlist' })
        setAll(false)
      })
    return () => { dead = true }
  }, [analysis.url])

  const commonFormats = uniformity.common || []
  const sharedLabel = sharedAudio
    ? audioFormat.toUpperCase()
    : `${height ?? '—'}p · ${format.container.toUpperCase()}`

  const defaults = () => ({
    kind: audioOnly ? 'audio' : mode,
    height: null,
    audioFormat,
    format: defaultFormat(),
  })
  const cfgOf = (index) => perItem[index] || defaults()

  /** Probe one entry so we can offer the resolutions it actually has. */
  async function probe(item, force = false) {
    if (!item.url) return
    if (!force && (probes[item.index]?.formats || probes[item.index]?.loading)) return
    setProbes((p) => ({ ...p, [item.index]: { loading: true } }))
    try {
      const info = await analyze(item.url)
      const formats = info.videoFormats || []
      setProbes((p) => ({ ...p, [item.index]: { loading: false, formats } }))
      setPerItem((p) => {
        const cur = p[item.index] || defaults()
        const stillValid = formats.some((f) => f.height === cur.height)
        return { ...p, [item.index]: { ...cur, height: stillValid ? cur.height : (formats[0]?.height ?? null) } }
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
    if (zipMode) {
      onStart({
        url: analysis.url,
        kind: sharedAudio ? 'audio' : 'video',
        height: sharedAudio ? null : height,
        container: sharedAudio ? null : format.container,
        codec: sharedAudio ? null : format.codec,
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
        container: itemAudio ? null : cfg.format.container,
        codec: itemAudio ? null : cfg.format.codec,
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

      {/* Shared settings exist only in zip mode — in multi-select each item carries its
          own type, format and quality. */}
      {zipMode && (
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
              <FormatPicker value={format} codecs={codecs} onChange={setFormat} />
              <div className="field">
                <label>Quality — every video in this playlist offers these</label>
                <div className="pills">
                  {commonFormats.map((f) => (
                    <button
                      key={f.height}
                      className={`pill ${height === f.height ? 'active' : ''}`}
                      onClick={() => setHeight(f.height)}
                    >
                      {f.label}
                      {f.note ? ` · ${f.note}` : ''}
                    </button>
                  ))}
                </div>
              </div>
            </>
          )}
        </>
      )}

      {uniformity.loading ? (
        <p className="pl-selnote">
          <i className="fa-solid fa-circle-notch fa-spin" /> Checking whether all {count} videos offer the same
          resolutions…
        </p>
      ) : zipOk ? (
        <label className="checkbox">
          <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
          <span>Download all {count} {count === 1 ? 'item' : 'items'} as a .zip</span>
        </label>
      ) : (
        <p className="pl-warn">
          <i className="fa-solid fa-circle-info" /> {uniformity.reason}
        </p>
      )}

      {!zipMode && (
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
                        <div className="pl-chips kind-row">
                          {!audioOnly && (
                            <>
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
                            </>
                          )}
                          {/* Re-reads just this video's options — the rest of the list stays put. */}
                          <button
                            className={`icon-round sm ${pr.loading ? 'spinning' : ''}`}
                            onClick={() => probe(it, true)}
                            disabled={pr.loading}
                            title="Re-check this video's formats"
                            aria-label={`Refresh formats for ${it.title}`}
                          >
                            <i className="fa-solid fa-rotate" />
                          </button>
                        </div>

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
                        ) : (
                          <div className="pl-adv">
                            <FormatPicker
                              value={cfg.format}
                              codecs={codecs}
                              compact
                              onChange={(f) => setItemField(it.index, 'format', f)}
                            />
                            {pr.loading ? (
                              <div className="pl-chips muted small">
                                <i className="fa-solid fa-circle-notch fa-spin" /> reading available qualities…
                              </div>
                            ) : pr.error ? (
                              <div className="pl-chips small" style={{ color: 'var(--accent-2)' }}>{pr.error}</div>
                            ) : pr.formats ? (
                              <div className="field">
                                <label>Quality</label>
                                <div className="pills">
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
                              </div>
                            ) : null}
                          </div>
                        )}
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

      <button
        className="btn btn-primary btn-lg"
        onClick={submit}
        disabled={uniformity.loading || (!zipMode && chosen.length === 0) || (zipMode && !sharedAudio && !height)}
      >
        <i className="fa-solid fa-download" />
        {zipMode ? `Download all ${count} · ${sharedLabel}` : `Download ${chosen.length || ''} selected`}
      </button>
    </section>
  )
}
