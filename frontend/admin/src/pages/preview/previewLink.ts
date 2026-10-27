import { onBeforeUnmount, ref, watch } from 'vue'
import { http } from '@/lib/http'
import { describeError } from '../experiments/experiments'
import { previewPageUrl, type PreviewTarget } from './preview'

/**
 * Preview tokens (POST /preview-token). A participant page that a researcher opens carries one of
 * these instead of the researcher's sign-in: it lets the page act as that one player of that one
 * session (for 12 hours), and nothing else. The page itself runs sandboxed in an opaque origin
 * (?preview=1), so whatever it shows can't reach the console, its cookies or its socket.
 */

export interface PreviewToken {
  token: string
  /** when the server stops accepting it (ISO time), if it said */
  expiresAt: string | null
}

export async function fetchPreviewToken (t: PreviewTarget): Promise<PreviewToken> {
  const res = await http.post<any>('/preview-token', {
    experimentId: t.experimentId,
    experimentInstanceId: t.instanceId,
    clientId: t.playerId,
  })
  const token = res && typeof res.token === 'string' ? res.token : ''
  if (!token) throw new Error('The server sent no preview token. Reload the console and try again.')
  return { token, expiresAt: res && typeof res.expiresAt === 'string' ? res.expiresAt : null }
}

/**
 * The address of one player's page for a preview, kept up to date with `target`: a new token is
 * fetched whenever the session or the player changes, and on retry. A component that is mounted
 * again (a reload) fetches a new one too. `src` stays empty until the token is there; `error` says
 * why there is none.
 */
export function usePreviewLink (target: () => PreviewTarget | null) {
  const src = ref('')
  const error = ref('')
  const loading = ref(false)
  // only the newest request may set the address: an older answer can arrive after a newer one
  let seq = 0

  async function load () {
    const t = target()
    const mine = ++seq
    src.value = ''
    error.value = ''
    loading.value = !!t
    if (!t) return
    try {
      const { token } = await fetchPreviewToken(t)
      if (mine === seq) src.value = previewPageUrl(t, token)
    } catch (e) {
      if (mine === seq) error.value = describeError(e, 'Couldn’t get a preview link from the server.')
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  const key = () => {
    const t = target()
    return t ? `${t.experimentId}/${t.instanceId}/${t.playerId}` : ''
  }
  watch(key, () => { load() }, { immediate: true })
  onBeforeUnmount(() => { seq++ })

  return { src, error, loading, retry: load }
}
