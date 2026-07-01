// Thin wrapper around the backend REST API. All URLs are relative so the same
// build works behind the Vite dev proxy and when served by Spring in production.
// Session cookie is sent automatically (same origin), so no auth headers here.

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function toError(res) {
  let msg = `Request failed (${res.status})`
  try {
    const body = await res.json()
    msg = body.message || body.error || msg
  } catch {
    // non-JSON error body
  }
  const err = new Error(msg)
  err.status = res.status
  return err
}

// ---- auth ----

export async function checkAuth() {
  const res = await fetch('/api/me')
  return res.ok
}

export async function login(username, password) {
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ username, password }),
  })
  if (!res.ok) throw await toError(res)
  return true
}

export async function logout() {
  try {
    await fetch('/api/logout', { method: 'POST' })
  } catch {
    // ignore
  }
}

// ---- app ----

export async function analyze(url) {
  const res = await fetch('/api/analyze', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ url }),
  })
  if (!res.ok) throw await toError(res)
  return res.json()
}

export async function startJob(request) {
  const res = await fetch('/api/jobs', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(request),
  })
  if (!res.ok) throw await toError(res)
  return res.json()
}

export async function getYtdlpStatus(refresh = false) {
  const res = await fetch('/api/ytdlp/status' + (refresh ? '?refresh=true' : ''))
  if (!res.ok) throw await toError(res)
  return res.json()
}

export async function pauseJob(id) {
  await fetch(`/api/jobs/${id}/pause`, { method: 'POST' })
}

export async function resumeJob(id) {
  await fetch(`/api/jobs/${id}/resume`, { method: 'POST' })
}

export async function cancelJob(id) {
  await fetch(`/api/jobs/${id}/cancel`, { method: 'POST' })
}

export function fileUrl(id) {
  return `/api/jobs/${id}/file`
}
