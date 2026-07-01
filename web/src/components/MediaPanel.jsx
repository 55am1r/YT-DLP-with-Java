import { useState } from 'react'
import { fmtDuration, fmtSize } from '../utils'

const AUDIO_FORMATS = ['mp3', 'm4a', 'opus', 'wav']
const CONTAINERS = ['mp4', 'mkv', 'webm']

export default function MediaPanel({ analysis, onStart }) {
  const [mode, setMode] = useState(analysis.music ? 'audio' : 'video')
  const [height, setHeight] = useState(analysis.videoFormats?.[0]?.height ?? 1080)
  const [container, setContainer] = useState('mp4')
  const [audioFormat, setAudioFormat] = useState('mp3')
  const [playlist, setPlaylist] = useState(false)

  function submit() {
    onStart({
      url: analysis.url,
      kind: mode,
      height: mode === 'video' ? height : null,
      container: mode === 'video' ? container : null,
      audioFormat: mode === 'audio' ? audioFormat : null,
      playlist: analysis.playlist ? playlist : false,
    })
  }

  return (
    <section className="panel">
      <div className="media">
        {analysis.thumbnail ? (
          <img className="thumb" src={analysis.thumbnail} alt="" />
        ) : (
          <div className="thumb thumb-empty">♪</div>
        )}
        <div className="meta">
          <h2 className="title">{analysis.title || 'Untitled'}</h2>
          <p className="muted">
            {analysis.uploader && <span>{analysis.uploader}</span>}
            {analysis.playlist
              ? <span> · Playlist{analysis.playlistCount ? ` · ${analysis.playlistCount} videos` : ''}</span>
              : analysis.durationSeconds != null && <span> · {fmtDuration(analysis.durationSeconds)}</span>}
            {analysis.music && <span className="tag">music</span>}
          </p>
        </div>
      </div>

      <div className="seg">
        <button className={`seg-btn ${mode === 'video' ? 'active' : ''}`} onClick={() => setMode('video')}>
          🎬 Video
        </button>
        <button className={`seg-btn ${mode === 'audio' ? 'active' : ''}`} onClick={() => setMode('audio')}>
          🎵 Audio
        </button>
      </div>

      {mode === 'video' ? (
        <>
          <div className="field">
            <label>Quality</label>
            <div className="pills">
              {analysis.videoFormats.map((f) => (
                <button
                  key={f.height}
                  className={`pill ${height === f.height ? 'active' : ''}`}
                  onClick={() => setHeight(f.height)}
                >
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
                <button
                  key={c}
                  className={`pill ${container === c ? 'active' : ''}`}
                  onClick={() => setContainer(c)}
                >
                  {c.toUpperCase()}
                </button>
              ))}
            </div>
          </div>
        </>
      ) : (
        <div className="field">
          <label>Audio format</label>
          <div className="pills">
            {AUDIO_FORMATS.map((a) => (
              <button
                key={a}
                className={`pill ${audioFormat === a ? 'active' : ''}`}
                onClick={() => setAudioFormat(a)}
              >
                {a.toUpperCase()}
              </button>
            ))}
          </div>
        </div>
      )}

      {analysis.playlist && (
        <label className="checkbox">
          <input type="checkbox" checked={playlist} onChange={(e) => setPlaylist(e.target.checked)} />
          <span>
            Download the entire playlist
            {analysis.playlistCount ? ` (${analysis.playlistCount} videos)` : ''} as a .zip
          </span>
        </label>
      )}

      <button className="btn btn-primary btn-lg" onClick={submit}>
        Download {mode === 'video' ? `${height}p · ${container.toUpperCase()}` : audioFormat.toUpperCase()}
      </button>
    </section>
  )
}
