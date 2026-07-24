import { useState } from 'react'

export default function UrlBar({ onAnalyze, analyzing }) {
  const [url, setUrl] = useState('')

  function submit(e) {
    e.preventDefault()
    const u = url.trim()
    if (!u) return
    onAnalyze(u)
    setUrl('') // the link now has its own tab — leave the bar ready for the next one
  }

  return (
    <form className="urlbar" onSubmit={submit}>
      <input
        className="input"
        type="text"
        placeholder="Paste a YouTube link — video, playlist, or music…"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        autoFocus
      />
      <button className="btn btn-primary" type="submit" disabled={analyzing || !url.trim()}>
        {analyzing ? 'Analyzing…' : 'Analyze'}
      </button>
    </form>
  )
}
