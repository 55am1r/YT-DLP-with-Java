// Small pieces every admin tab uses: status chips, stat tiles, empty states, download rows.
import { bytes, formatLabel, safeHref, statusMeta, timeAgo, ytThumb } from './format'

/** A state label: icon in the state's colour, words in text colour — never colour alone. */
export function Chip({ tone = 'muted', icon, children, title }) {
  return (
    <span className={`chip tone-${tone}`} title={title}>
      {icon && <i className={`fa-solid ${icon}`} aria-hidden="true" />}
      <span>{children}</span>
    </span>
  )
}

export function StatusChip({ status }) {
  const m = statusMeta(status)
  return <Chip tone={m.tone} icon={m.icon}>{m.label}</Chip>
}

export function Kpi({ label, value, sub, tone }) {
  return (
    <div className={`kpi glass ${tone ? `kpi-${tone}` : ''}`}>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value">{value}</span>
      {sub && <span className="kpi-sub">{sub}</span>}
    </div>
  )
}

export function Empty({ icon = 'fa-inbox', title, children }) {
  return (
    <div className="empty">
      <i className={`fa-solid ${icon}`} aria-hidden="true" />
      <strong>{title}</strong>
      {children && <span className="muted">{children}</span>}
    </div>
  )
}

/** A device's name as a button that opens its drawer. */
export function DeviceLink({ id, name, onSelect }) {
  if (!id) return <span className="muted">Unknown device</span>
  return (
    <button type="button" className="link-btn" onClick={() => onSelect(id)} title="Open this device">
      {name || 'Unknown device'}
    </button>
  )
}

/** One download: thumbnail, title (linked only when it's an http(s) URL), what and who. */
export function DownloadRow({ r, onSelectDevice, showDevice = true }) {
  const href = safeHref(r.url)
  const thumb = ytThumb(r.videoId)
  const saved = r.savedBy?.length || 0
  return (
    <li className="dl-row">
      <div className="dl-thumb" aria-hidden="true">
        {thumb
          ? <img src={thumb} alt="" loading="lazy" referrerPolicy="no-referrer" />
          : <i className={`fa-solid ${r.kind === 'audio' ? 'fa-music' : 'fa-film'}`} />}
      </div>
      <div className="dl-main">
        <div className="dl-title">
          {href
            ? <a href={href} target="_blank" rel="noopener noreferrer">{r.title || r.url}</a>
            : <span>{r.title || r.url || 'Untitled'}</span>}
        </div>
        <div className="dl-meta">
          <span>{formatLabel(r) || (r.kind === 'audio' ? 'Audio' : 'Video')}</span>
          {r.fileSize ? <span>{bytes(r.fileSize)}</span> : null}
          {showDevice && (
            <span>
              <DeviceLink id={r.deviceId} name={r.deviceName} onSelect={onSelectDevice} />
              {r.place ? <span className="muted"> · {r.place}</span> : null}
            </span>
          )}
          <span className="muted" title={new Date(r.at).toLocaleString()}>{timeAgo(r.at)}</span>
        </div>
        {r.status === 'FAILED' && r.error && <div className="dl-error">{r.error}</div>}
      </div>
      <div className="dl-side">
        <StatusChip status={r.status} />
        {saved > 0 && (
          <Chip tone="good" icon="fa-floppy-disk" title="Devices that saved the finished file">
            {saved === 1 ? 'Saved' : `Saved on ${saved}`}
          </Chip>
        )}
      </div>
    </li>
  )
}
