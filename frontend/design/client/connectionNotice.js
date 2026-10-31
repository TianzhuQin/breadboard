/**
 * One line above the page when it can't act for its player: another page has the seat (this one
 * stopped), or it is a researcher's preview that only watches: a player whose own page is connected,
 * or a computer player, which the computer plays (every preview of one only watches). The same words
 * as the Vue participant page's. {@code bb} is Breadboard (breadboard-core): its connection, preview
 * and computerPlayer.
 */
export function connectionNotice (bb) {
  if (!bb) return '';
  if (bb.connection === 'elsewhere') {
    // (a reloaded preview can't continue: it is opened again from the console)
    return bb.preview
      ? 'This player is open in another preview or tab.'
      : 'This study is open in another tab or window. Reload this page to use it here.';
  }
  if (bb.connection === 'watching') {
    return bb.computerPlayer
      ? 'Watching only: the computer plays this seat, so you see what it does but can’t answer for it.'
      : 'Watching only: this player’s own page is connected, so you see their screen but can’t answer for them.';
  }
  return '';
}
