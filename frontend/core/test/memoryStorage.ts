/**
 * Local storage for the tests (vi.stubGlobal('localStorage', new MemoryStorage())). From version 25,
 * Node's own localStorage hides jsdom's, and has no methods without a --localstorage-file.
 */
export class MemoryStorage {
  private items = new Map<string, string>()
  get length () { return this.items.size }
  key (index: number) { return Array.from(this.items.keys())[index] ?? null }
  getItem (key: string) { return this.items.has(key) ? this.items.get(key)! : null }
  setItem (key: string, value: string) { this.items.set(key, String(value)) }
  removeItem (key: string) { this.items.delete(key) }
  clear () { this.items.clear() }
}
