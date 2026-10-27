import { actions } from '@/store/admin'

/**
 * An earlier session's recorded events (ws ShowEvent). The server's reply carries no request id,
 * so two requests in flight could be confused with each other; requests here run one at a time.
 */
let queue: Promise<unknown> = Promise.resolve()

export function loadSessionEvents (id: number): Promise<any[] | null> {
  const next = queue.then(async () => {
    const res = await actions.instanceEvents(id)
    return res && Array.isArray(res.events) ? res.events : null
  })
  queue = next.catch(() => null)
  return next
}
