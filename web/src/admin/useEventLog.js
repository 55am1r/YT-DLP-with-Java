import { useEffect, useState } from 'react'
import { getEvents } from './adminApi'
import { usePager } from './paging'

/**
 * The activity log ('all' or 'security'), paged by the server. `tick` is the dashboard's last
 * refresh: page 1 follows it so new events appear live, later pages hold still while read.
 */
export function useEventLog(kind, { id, tick }) {
  const [result, setResult] = useState({ total: 0, items: [] })
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(null)
  const pager = usePager(result.total, { id })
  const live = pager.page === 1 ? tick : 0

  useEffect(() => {
    let alive = true
    getEvents(kind, pager.offset, pager.size)
      .then((p) => { if (alive) { setResult(p); setError(null) } })
      .catch((e) => { if (alive) setError(e.message || 'Could not load the log') })
      .finally(() => { if (alive) setLoaded(true) })
    return () => { alive = false }
  }, [kind, pager.offset, pager.size, live])

  return { ...result, loaded, error, pager }
}
