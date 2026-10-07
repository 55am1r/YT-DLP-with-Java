// Calls to the admin API (/api/admin/**). The server only answers them for an admin
// session; a 401 here means the session ended and the panel hands back to the login.
import { toError } from '../api'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function get(path) {
  const res = await fetch(path, { cache: 'no-store' })
  if (!res.ok) throw await toError(res)
  return res.json()
}

async function post(path, body) {
  const res = await fetch(path, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body ?? {}) })
  if (!res.ok) throw await toError(res)
  return res.json()
}

/** Only the filters that are actually set — the server treats a missing one as "any". */
function query(params) {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '' && v !== 0) q.set(k, v)
  })
  return q.toString()
}

export const getDashboard = () => get('/api/admin/dashboard')
export const getInsights = (days) => get(`/api/admin/insights?days=${days}`)
export const getDevice = (id) => get(`/api/admin/devices/${encodeURIComponent(id)}`)
export const getDownloads = (filters, offset = 0, limit = 50) =>
  get(`/api/admin/downloads?${query({ ...filters, offset, limit })}`)
export const downloadsCsvUrl = (filters) => `/api/admin/downloads/export?${query(filters)}`

export const updateDevice = (id, patch) => post(`/api/admin/devices/${encodeURIComponent(id)}`, patch)
export const setIpBlocked = (ip, blocked) => post('/api/admin/ips/block', { ip, blocked })
export const unlockIp = (ip) => post('/api/admin/ips/unlock', { ip })
export const cancelJob = (id) => post(`/api/admin/jobs/${encodeURIComponent(id)}/cancel`)
export const postAnnouncement = (text, level) => post('/api/admin/announcement', { text, level })
