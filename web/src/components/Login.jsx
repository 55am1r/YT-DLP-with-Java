import { useState } from 'react'
import { login } from '../api'

/**
 * One login for everyone: the team's credentials open the downloader, the owner's open
 * the admin panel. Nothing on this screen hints that an admin login exists.
 */
export default function Login({ onSuccess, blocked = false, note = null }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onSuccess(await login(username.trim(), password))
    } catch (err) {
      setError(err.message || 'Login failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="login-logo" aria-hidden="true"><i className="fa-solid fa-circle-down" /></div>
        <h1>EZ-Tube</h1>
        <p className="muted">Enter the team login to continue</p>
        {blocked && !error && <div className="error">Access to EZ-Tube has been blocked by the admin.</div>}
        {note && !blocked && !error && <p className="muted login-note">{note}</p>}
        <input
          className="input"
          placeholder="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
        />
        <input
          className="input"
          type="password"
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          autoFocus
        />
        {error && <div className="error">{error}</div>}
        <button className="btn btn-primary btn-lg" disabled={busy || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
