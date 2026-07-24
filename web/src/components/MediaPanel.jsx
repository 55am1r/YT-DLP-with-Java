import { useState } from 'react'
import { fmtDuration, fmtSize } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']
const CONTAINERS = ['mp4', 'mkv', 'webm']

/** Single video: 21:9 hero, then title/length, then the download options. */
export default function MediaPanel({ analysis, onStart }) {
  const audioOnly = analysis.music // music sources never show video options
  const [mode, setMode] = useState(audioOnly ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [container, setContainer] = useState('mp4')
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')

  const isAudio = audioOnly || mode === 'audio'

  function submit() {
    onStart({
      url: analysis.url,
      kind: isAudio ? 'audio' : 'video',
      height: isAudio ? null : height,
      container: isAudio ? null : container,
      audioFormat: isAudio ? audioFormat : null,
      playlist: false,
      title: analysis.title,
      startTime: start.trim() || null,
      endTime: end.trim() || null,
    })
  }

  const trimmed = start.trim() || end.trim()

  return (
    <section className="panel glass">
      {analysis.thumbnail ? (
        <img className="hero" src={analysis.thumbnail} alt="" />
      ) : (
        <div className="hero hero-empty">♪</div>
      )}

      <div className="media-head">
        <h2 className="title">{analysis.title || 'Untitled'}</h2>
        <p className="muted">
          {analysis.uploader}
          {analysis.durationSeconds != null && <span> · {fmtDuration(analysis.durationSeconds)}</span>}
          {audioOnly && <span className="tag">music</span>}
        </p>
      </div>

      {!audioOnly && (
        <div className="seg glass">
          <button className={`seg-btn ${mode === 'video' ? 'active' : ''}`} onClick={() => setMode('video')}>🎬 Video</button>
          <button className={`seg-btn ${mode === 'audio' ? 'active' : ''}`} onClick={() => setMode('audio')}>♪ Audio</button>
        </div>
      )}

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
          <div className="field">
            <label>Quality</label>
            <div className="pills">
              {analysis.videoFormats.map((f) => (
                <button key={f.height} className={`pill ${height === f.height ? 'active' : ''}`} onClick={() => setHeight(f.height)}>
                  {f.label}
                  {f.note ? ` · ${f.note}` : ''}
                  {f.filesize ? <span className="pill-sub">{fmtSize(f.filesize)}</span> : null}
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

      <div className="field">
        <label>Trim (optional) — leave empty for the whole {isAudio ? 'track' : 'video'}</label>
        <div className="clip-row">
          <input className="clip-input" placeholder="start 0:30" value={start} onChange={(e) => setStart(e.target.value)} />
          <span className="muted">→</span>
          <input className="clip-input" placeholder="end 1:45" value={end} onChange={(e) => setEnd(e.target.value)} />
        </div>
      </div>

      <button className="btn btn-primary btn-lg" onClick={submit}>
        Download {isAudio ? audioFormat.toUpperCase() : `${height}p · ${container.toUpperCase()}`}
        {trimmed ? ' (clip)' : ''}
      </button>
    </section>
  )
}
