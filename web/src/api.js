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

/**
 * Queue a download. The server answers 409 when an identical file is already
 * downloading or finished; that comes back as {duplicate, job} so the caller can ask
 * the user before spending the bandwidth and disk a second time.
 */
export async function startJob(request, force = false) {
  const res = await fetch('/api/jobs' + (force ? '?force=true' : ''), {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(request),
  })
  if (res.status === 409) {
    const body = await res.json()
    return { duplicate: true, job: body.job }
  }
  if (!res.ok) throw await toError(res)
  return res.json()
}

/** Compression options this server's ffmpeg can actually produce. */
export async function getCodecs() {
  const res = await fetch('/api/codecs')
  if (!res.ok) throw await toError(res)
  return res.json()
}

/** Whether every video in a playlist shares the same resolutions (gates the zip option). */
export async function getPlaylistFormats(url) {
  const res = await fetch(`/api/playlist/formats?url=${encodeURIComponent(url)}`)
  if (!res.ok) throw await toError(res)
  return res.json()
}

export async function getYtdlpStatus(refresh = false) {
  const res = await fetch('/api/ytdlp/status' + (refresh ? '?refresh=true' : ''))
  if (!res.ok) throw await toError(res)
  return res.json()
}

/** Clear the server files for one link's downloads only. */
export async function clearJobs(ids) {
  const res = await fetch('/api/jobs/clear', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ ids }),
  })
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
