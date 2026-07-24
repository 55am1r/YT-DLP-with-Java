import { secondsToClock } from '../utils'

/**
 * Dual-handle range over the video's timeline — drag the ends instead of typing
 * timestamps. Two overlaid range inputs; only the thumbs take pointer events, so
 * both handles stay grabbable.
 */
export default function TrimSlider({ duration, start, end, onChange }) {
  const max = Math.max(1, Math.round(duration || 0))
  const s = Math.max(0, Math.min(start, max - 1))
  const e = Math.max(s + 1, Math.min(end, max))
  const pctS = (s / max) * 100
  const pctE = (e / max) * 100

  return (
    <div className="trim">
      <div className="trim-track">
        <div className="trim-range" style={{ left: `${pctS}%`, right: `${100 - pctE}%` }} />
        <input
          className="trim-input"
          type="range"
          min={0}
          max={max}
          value={s}
          aria-label="Clip start"
          onChange={(ev) => onChange(Math.min(Number(ev.target.value), e - 1), e)}
        />
        <input
          className="trim-input"
          type="range"
          min={0}
          max={max}
          value={e}
          aria-label="Clip end"
          onChange={(ev) => onChange(s, Math.max(Number(ev.target.value), s + 1))}
        />
      </div>
      <div className="trim-labels">
        <span className="trim-time"><i className="fa-solid fa-play" /> {secondsToClock(s)}</span>
        <span className="trim-dur">{secondsToClock(e - s)} selected</span>
        <span className="trim-time">{secondsToClock(e)} <i className="fa-solid fa-flag-checkered" /></span>
      </div>
    </div>
  )
}
