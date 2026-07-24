import { useEffect } from 'react'

/**
 * Modal shown when a requested download already exists on the server, so the team
 * doesn't fetch and store the same 4K file twice. Escape and the backdrop both mean
 * "keep the existing one" — the safe, no-extra-load answer.
 */
export default function ConfirmDialog({ title, message, detail, confirmLabel, cancelLabel, onConfirm, onCancel }) {
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal glass" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon"><i className="fa-solid fa-clone" /></div>
        <h3 className="modal-title">{title}</h3>
        <p className="modal-msg">{message}</p>
        {detail && <p className="modal-detail">{detail}</p>}
        <div className="modal-actions">
          <button className="btn btn-primary" onClick={onCancel}>
            <i className="fa-solid fa-check" /> {cancelLabel}
          </button>
          <button className="btn btn-ghost danger" onClick={onConfirm}>
            <i className="fa-solid fa-rotate-right" /> {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
