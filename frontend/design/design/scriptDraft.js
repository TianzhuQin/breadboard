/**
 * The Script panel edits breadboard.user.currentScript, which a User JSON from the server replaces
 * with the script last run. The server also sends one unprompted (a session of the experiment opened
 * or ended, another tab changed something), and the researcher's unsaved draft was lost each time.
 * Before such a frame is merged, the draft is put into it; a User JSON of another account is taken
 * as it is. Returns {@code data}.
 */
export function keepScriptDraft(user, data) {
  if (!user || !data || !data.user || typeof data.user !== 'object' || user.email !== data.user.email) {
    return data;
  }
  if (typeof user.currentScript === 'string') {
    data.user.currentScript = user.currentScript;
  }
  return data;
}
