import { describe, expect, it } from 'vitest'
import { BreadboardClass } from '../src/breadboard'

describe('sendEdgeEdit', () => {
  it('sends a bb:edge-edit custom event on behalf of this player', async () => {
    const bb = new BreadboardClass() as any
    bb.config = { clientId: 'p1' }
    const sent: any[] = []
    bb.sendCustom = (payload: any) => { sent.push(payload) }
    await bb.sendEdgeEdit('remove', 'nabc2345', 'ndef6789')
    expect(sent).toEqual([{ playerId: 'p1', eventName: 'bb:edge-edit', data: { op: 'remove', a: 'nabc2345', b: 'ndef6789' } }])
  })
})
