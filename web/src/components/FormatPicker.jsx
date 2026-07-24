import { useEffect } from 'react'

const CONTAINERS = ['mp4', 'mkv', 'webm']

// Three things are worth pointing at: the untouched original, the smallest result,
// and the one that balances both well enough to be the everyday choice.
const BADGES = {
  'Best quality': { cls: 'quality', icon: 'fa-solid fa-gem' },
  'Best savings': { cls: 'save', icon: 'fa-solid fa-compress' },
  Recommended: { cls: 'rec', icon: 'fa-solid fa-bolt' },
}

function Badge({ text }) {
  const b = BADGES[text] || BADGES.Recommended
  return (
    <span className={`codec-badge ${b.cls}`}>
      <i className={b.icon} /> {text}
    </span>
  )
}

/**
 * The Auto / Advanced switch that sits between picking video and picking a resolution.
 *
 *   Auto      → MP4, no re-encode. Exactly what the app did before this existed.
 *   Advanced  → container, then compression model.
 *
 * Used unchanged for the single-video panel and for each ticked playlist item, so the
 * hierarchy reads the same everywhere: video → auto/advanced → resolution → download.
 *
 * @param value {{mode, container, codec}}
 * @param codecs compression options the server reported — never a hardcoded list, since
 *               what ffmpeg on the Mac supports is the only thing that matters
 * @param compact smaller pills, for the playlist rows
 */
export default function FormatPicker({ value, codecs, onChange, compact = false }) {
  const { mode, container, codec } = value
  const sm = compact ? ' sm' : ''

  // Not every codec fits every container — H.265 has no place in a WEBM file. Rather
  // than let the server reject it later, offer only what the chosen container holds.
  const usable = codecs.filter((c) => c.containers.includes(container))
  const chosen = usable.find((c) => c.id === codec)

  useEffect(() => {
    if (mode === 'advanced' && codec !== 'none' && !usable.some((c) => c.id === codec)) {
      onChange({ ...value, codec: 'none' })
    }
  }, [container]) // eslint-disable-line react-hooks/exhaustive-deps

  function setMode(next) {
    onChange(next === 'auto'
      ? { mode: 'auto', container: 'mp4', codec: 'none' }
      : { ...value, mode: 'advanced' })
  }

  return (
    <>
      <div className="field">
        <label>Format</label>
        <div className={`seg glass${compact ? ' seg-sm' : ''}`}>
          <button className={`seg-btn ${mode === 'auto' ? 'active' : ''}`} onClick={() => setMode('auto')}>
            <i className="fa-solid fa-wand-magic-sparkles" /> Auto
          </button>
          <button className={`seg-btn ${mode === 'advanced' ? 'active' : ''}`} onClick={() => setMode('advanced')}>
            <i className="fa-solid fa-sliders" /> Advanced
          </button>
        </div>
        {mode === 'auto' && (
          <p className="hint-line">
            <i className="fa-regular fa-circle-check" /> MP4, original quality — nothing re-encoded
          </p>
        )}
      </div>

      {mode === 'advanced' && (
        <>
          <div className="field">
            <label>Container</label>
            <div className="pills">
              {CONTAINERS.map((c) => (
                <button
                  key={c}
                  className={`pill${sm} ${container === c ? 'active' : ''}`}
                  onClick={() => onChange({ ...value, container: c })}
                >
                  {c.toUpperCase()}
                </button>
              ))}
            </div>
          </div>

          <div className="field">
            <label>Compression</label>
            <div className="pills pills-wide">
              {usable.map((c) => (
                <button
                  key={c.id}
                  className={`pill codec-pill${sm} ${codec === c.id ? 'active' : ''}`}
                  onClick={() => onChange({ ...value, codec: c.id })}
                  title={c.note}
                >
                  <span className="codec-main">
                    {c.label}
                    {c.badge && <Badge text={c.badge} />}
                  </span>
                  {/* Just the figure — the full trade-off is spelled out in the hint
                      below, so the chip stays one short line. */}
                  <span className="pill-sub">{c.sizeHint}</span>
                </button>
              ))}
            </div>
            {/* Spell out the actual trade being made: picture quality, disk space, and
                how long the job takes. */}
            <p className="hint-line">
              {codec === 'none'
                ? 'Best possible quality and the quickest to finish — it just takes the most space.'
                : `${chosen?.note}. Expect ${chosen?.sizeHint} of the original size, encoded after the download.`}
            </p>
          </div>
        </>
      )}
    </>
  )
}

/** Starting point for a fresh picker: matches the old always-MP4-lossless behaviour. */
export const defaultFormat = () => ({ mode: 'auto', container: 'mp4', codec: 'none' })
