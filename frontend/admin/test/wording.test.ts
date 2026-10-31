import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { BUILTINS } from '../src/pages/steps/stepsModel'
import { SNIPPETS } from '../src/pages/console/snippets'

// The Live session button reads "Start the game"; the Steps page and the Script console's Library
// called it "Start game".
describe('the button that starts the game', () => {
  it('is called Start the game where the console mentions it', () => {
    expect(BUILTINS.find(b => b.name === 'InitStep')!.detail).toContain('when you press Start the game on the Live session page')
    expect(SNIPPETS.find(s => s.id === 'start')!.text).toMatch(/^The same as Start the game on the Live session page:/)
  })

  it('is never called Start game', () => {
    const src = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
    const files: string[] = []
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name)
        if (statSync(path).isDirectory()) walk(path)
        else if (/\.(ts|vue)$/.test(name)) files.push(path)
      }
    }
    walk(src)
    expect(files.length).toBeGreaterThan(50)
    expect(files.filter(f => /\bStart game\b/.test(readFileSync(f, 'utf8'))).map(f => f.slice(src.length + 1))).toEqual([])
  })
})
