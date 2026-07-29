import { useEffect, useState } from 'react'
import TrimSlider from './TrimSlider'
import FormatPicker, { defaultFormat } from './FormatPicker'
import { fmtDuration, fmtSize, parseUrlTimestamp } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']

/**
 * Single video. Settings are seeded from `config` (the tab's persisted state) and
 * synced back through `onConfig`, so switching tabs or refreshing keeps every choice.
 */
export default function MediaPanel({ analysis, onStart, codecs, onRefresh, refreshing, config, onConfig }) {
  const cfg = config || null
  const audioOnly = analysis.music
  const duration = Math.round(analysis.durationSeconds || 0)

  // The video's native aspect. The frame takes this shape so the whole thumbnail shows
  // at any width, with no black bars and no gradient backdrop — just the image.
  const ratio = analysis.videoWidth && analysis.videoHeight
    ? analysis.videoWidth / analysis.videoHeight
    : null

  // The loaded image's own ratio wins over the video's, because YouTube sometimes ships
  // a padded thumbnail (e.g. 4:3 for a vertical Short); matching the image means it
  // fills the frame exactly.
  const [imgRatio, setImgRatio] = useState(null)
  const frameRatio = imgRatio || ratio

  function measure(e) {
    const { naturalWidth: w, naturalHeight: h } = e.currentTarget
    if (w > 0 && h > 0) setImgRatio(w / h)
  }

  const [mode, setMode] = useState(cfg?.mode ?? (audioOnly ? 'audio' : 'video'))
  const [height, setHeight] = useState(cfg?.height ?? (analysis.videoFormats?.[0]?.height ?? 1080))
  const [format, setFormat] = useState(cfg?.format ?? defaultFormat())
  const [audioFormat, setAudioFormat] = useState(cfg?.audioFormat ?? 'mp3')
  const [trimOn, setTrimOn] = useState(cfg?.trimOn ?? false)
  const [range, setRange] = useState(cfg?.range ?? [0, duration || 1])

  // A pasted link may already carry a start time (?t=90). Open trim pre-set to it — but
  // only for a fresh tab, never overriding a restored choice.
  const linkStart = parseUrlTimestamp(analysis.url)
  const hasLinkStart = linkStart != null && duration > 0 && linkStart < duration
  const [linkApplied, setLinkApplied] = useState(false)
  if (!cfg && hasLinkStart && !linkApplied) {
    setLinkApplied(true)
    setTrimOn(true)
    setRange([linkStart, duration])
  }

  // Push the current settings up so the tab remembers them.
  useEffect(() => {
    onConfig?.({ mode, height, format, audioFormat, trimOn, range })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, height, format, audioFormat, trimOn, range])

  const isAudio = audioOnly || mode === 'audio'
  const [start, end] = range
  const wholeThing = start <= 0 && end >= duration

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
      {/* Frame is the thumbnail's own shape — no backdrop, no bars. */}
      <div className="hero-wrap" style={frameRatio ? { '--ar': String(frameRatio) } : undefined}>
        {analysis.thumbnail ? (
          <img
            className="hero-img"
            src={analysis.thumbnail}
            srcSet={analysis.thumbnailSrcset || undefined}
            sizes="(max-width: 750px) 100vw, 560px"
            alt=""
            onLoad={measure}
          />
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
