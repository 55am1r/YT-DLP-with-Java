import { useState } from 'react'
import TrimSlider from './TrimSlider'
import FormatPicker, { defaultFormat } from './FormatPicker'
import { fmtDuration, fmtSize, parseUrlTimestamp } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']

/** Single video: hero shaped to the video's real aspect, then title/length, then options. */
export default function MediaPanel({ analysis, onStart, codecs, onRefresh, refreshing }) {
  const audioOnly = analysis.music
  const duration = Math.round(analysis.durationSeconds || 0)

  // Shorts and phone footage are vertical; forcing them into a wide box cropped the
  // frame. Shape the hero to the source instead, and letterbox the rest with a
  // blurred copy of the thumbnail so the panel keeps its width either way.
  const ratio = analysis.videoWidth && analysis.videoHeight
    ? analysis.videoWidth / analysis.videoHeight
    : null
  const portrait = ratio != null && ratio < 1

  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [format, setFormat] = useState(defaultFormat)
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [trimOn, setTrimOn] = useState(false)
  const [range, setRange] = useState([0, duration || 1])

  // A pasted link may already carry a start time (?t=90). If so, open the trim
  // controls pre-set to it instead of making the user re-enter anything.
  const linkStart = parseUrlTimestamp(analysis.url)
  const hasLinkStart = linkStart != null && duration > 0 && linkStart < duration
  const [linkApplied, setLinkApplied] = useState(false)
  if (hasLinkStart && !linkApplied) {
    setLinkApplied(true)
    setTrimOn(true)
    setRange([linkStart, duration])
  }

  const isAudio = audioOnly || mode === 'audio'
  const [start, end] = range
  const wholeThing = start <= 0 && end >= duration

  // The chosen height may not exist any more after a refresh — fall back to the best.
  const heights = (analysis.videoFormats || []).map((f) => f.height)
  const activeHeight = heights.includes(height) ? height : (heights[0] ?? height)

  function submit() {
    onStart({
      url: analysis.url,
      kind: isAudio ? 'audio' : 'video',
      height: isAudio ? null : activeHeight,
      container: isAudio ? null : format.container,
      codec: isAudio ? null : format.codec,
      audioFormat: isAudio ? audioFormat : null,
      playlist: false,
      title: analysis.title,
      startTime: trimOn && !wholeThing ? String(start) : null,
      endTime: trimOn && !wholeThing ? String(end) : null,
    })
  }

  return (
    <section className="panel glass">
      <div
        className={`hero-wrap ${portrait ? 'portrait' : ''}`}
        style={ratio && !portrait ? { aspectRatio: String(ratio) } : undefined}
      >
        {analysis.thumbnail ? (
          <>
            <img className="hero-bg" src={analysis.thumbnail} alt="" aria-hidden="true" />
            <img
              className="hero-img"
              src={analysis.thumbnail}
              alt=""
              style={portrait ? { aspectRatio: String(ratio) } : undefined}
            />
          </>
        ) : (
          <div className="hero-empty"><i className="fa-solid fa-music" /></div>
        )}
        {ratio && <span className="hero-dim">{analysis.videoWidth}×{analysis.videoHeight}</span>}
      </div>

      <div className="media-head">
        <h2 className="title">{analysis.title || 'Untitled'}</h2>
        <p className="muted">
          {analysis.uploader}
          {analysis.durationSeconds != null && <span> · {fmtDuration(analysis.durationSeconds)}</span>}
          {audioOnly && <span className="tag">music</span>}
        </p>
      </div>

      <div className="kind-row">
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
        {/* Re-reads this one link's options from the server. Nothing else on the page
            reloads — other tabs and their downloads carry on untouched. */}
        <button
          className={`icon-round ${refreshing ? 'spinning' : ''}`}
          onClick={onRefresh}
          disabled={refreshing}
          title="Re-check the formats available for this link"
          aria-label="Refresh available formats"
        >
          <i className="fa-solid fa-rotate" />
        </button>
      </div>

      {isAudio ? (
        <div className="field">
          <label>Audio format</label>
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
            <label>Quality</label>
            <div className="pills">
              {analysis.videoFormats.map((f) => (
                <button
                  key={f.height}
                  className={`pill ${activeHeight === f.height ? 'active' : ''}`}
                  onClick={() => setHeight(f.height)}
                >
                  {f.label}
                  {f.note ? ` · ${f.note}` : ''}
                  {f.filesize ? <span className="pill-sub">{fmtSize(f.filesize)}</span> : null}
                </button>
              ))}
            </div>
          </div>
        </>
      )}

      {duration > 0 && (
        <div className="field">
          {!trimOn ? (
            <button className="btn btn-sm" onClick={() => { setRange([0, duration]); setTrimOn(true) }}>
              <i className="fa-solid fa-scissors" /> Trim this {isAudio ? 'track' : 'video'}
            </button>
          ) : (
            <>
              <label>
                <i className="fa-solid fa-scissors" /> Trim
                {hasLinkStart && <span className="trim-hint"> · start taken from your link</span>}
              </label>
              <TrimSlider duration={duration} start={start} end={end} onChange={(s, e) => setRange([s, e])} />
              <div className="trim-actions">
                <button className="btn btn-sm" onClick={() => setRange([0, duration])}>
                  <i className="fa-solid fa-arrows-left-right-to-line" /> Whole {isAudio ? 'track' : 'video'}
                </button>
                <button className="btn btn-sm btn-ghost danger" onClick={() => { setTrimOn(false); setRange([0, duration]) }}>
                  <i className="fa-solid fa-rotate-left" /> Cancel trim
                </button>
              </div>
            </>
          )}
        </div>
      )}

      <button className="btn btn-primary btn-lg" onClick={submit}>
        <i className="fa-solid fa-download" />
        Download {isAudio
          ? audioFormat.toUpperCase()
          : `${activeHeight}p · ${format.container.toUpperCase()}${format.codec !== 'none' ? ` · ${format.codec.toUpperCase()}` : ''}`}
        {trimOn && !wholeThing ? ' (clip)' : ''}
      </button>
    </section>
  )
}
