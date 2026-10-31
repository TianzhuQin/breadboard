import { describe, it, expect } from 'vitest'
import { safeFileName } from '../src/pages/sessions/sessionData'
import { safeFileName as liveFileName } from '../src/pages/session/live'

// The names of the data files Sessions & data and Live session download. The autopilot names its
// sessions with no-break spaces, which keep "Autopilot ·" and "13:20:47 UTC" in one piece in the
// console's narrow columns: each of them became a "-" in the file's name ("Autopilot- 2026-10-30
// 13-20-47-UTC_14.csv").
describe('download file names', () => {
  it('turn any kind of space into a plain one', () => {
    const name = 'Autopilot · 2026-10-30 13:20:47 UTC'
    expect(safeFileName(name)).toBe('Autopilot - 2026-10-30 13-20-47 UTC')
    expect(liveFileName(name)).toBe('Autopilot - 2026-10-30 13-20-47 UTC')
    expect(safeFileName('a b c\td')).toBe('a b c d')
    expect(liveFileName('a b c\td')).toBe('a b c d')
  })

  it('name the autopilot\'s older sessions as before', () => {
    expect(safeFileName('Autopilot · Oct 28, 14:11:04 UTC')).toBe('Autopilot - Oct 28 14-11-04 UTC')
    expect(liveFileName('Autopilot · Oct 28, 14:11:04 UTC')).toBe('Autopilot - Oct 28 14-11-04 UTC')
  })
})
