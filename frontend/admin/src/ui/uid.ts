let n = 0
/** A page-unique id for wiring labels, descriptions and popups to their controls. */
export function uid (prefix = 'b'): string {
  n += 1
  return `${prefix}-${n}`
}
