/**
 * Calls to the server's HTTP routes (conf/routes). Every route the console uses sits behind the
 * admin session cookie; a 401 means the session is gone, which the app answers by showing the
 * sign-in page (see onUnauthorized).
 */
export class HttpError extends Error {
  constructor (public status: number, message: string, public body?: any) {
    super(message)
  }
}

type UnauthorizedHandler = (body: any) => void
let unauthorized: UnauthorizedHandler = () => {}
export function onUnauthorized (fn: UnauthorizedHandler) { unauthorized = fn }

async function request<T> (method: string, url: string, body?: any, opts: { raw?: boolean, form?: boolean, quiet401?: boolean } = {}): Promise<T> {
  const init: RequestInit = { method, credentials: 'same-origin', headers: {} }
  if (body instanceof FormData) {
    init.body = body
  } else if (opts.form && body) {
    init.body = new URLSearchParams(body)
    ;(init.headers as any)['Content-Type'] = 'application/x-www-form-urlencoded'
  } else if (body !== undefined) {
    init.body = JSON.stringify(body)
    ;(init.headers as any)['Content-Type'] = 'application/json'
  }
  const res = await fetch(url, init)
  const text = await res.text()
  let data: any = text
  try { data = text ? JSON.parse(text) : null } catch (e) { /* plain text */ }
  if (res.status === 401) {
    if (!opts.quiet401) unauthorized(data)
    throw new HttpError(401, (data && data.message) || 'Please sign in', data)
  }
  if (!res.ok) {
    const msg = (data && (data.message || data.error)) || (typeof data === 'string' && data) || res.statusText
    throw new HttpError(res.status, String(msg).slice(0, 500), data)
  }
  return (opts.raw ? text : data) as T
}

export const http = {
  get: <T = any>(url: string, opts?: { raw?: boolean, quiet401?: boolean }) => request<T>('GET', url, undefined, opts),
  post: <T = any>(url: string, body?: any, opts?: { raw?: boolean, form?: boolean, quiet401?: boolean }) => request<T>('POST', url, body, opts),
  put: <T = any>(url: string, body?: any) => request<T>('PUT', url, body),
  del: <T = any>(url: string) => request<T>('DELETE', url),
}

/** Save text as a file in the browser. */
export function saveText (fileName: string, text: string, type = 'text/csv') {
  const blob = new Blob([text], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
