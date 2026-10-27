import type { MturkAssignment } from './api'
import { bonusFromAnswers, moneyString, parseAnswers, type ParsedAnswers } from './lib'

/** One submitted assignment in a HIT's table, with what the console is doing to it. */
export interface AssignmentRow {
  id: string
  workerId: string
  approvalTime: string | null
  rejectionTime: string | null
  answers: ParsedAnswers
  /** the bonus the participant's submit form reported (answer field "bonus") */
  bonusAsked: number | null
  /** the amount to pay, as typed in the bonus dialog */
  bonusInput: string
  /** this worker finished the experiment (drives "block previous players") */
  completed: boolean
  /** 'queued' while a bulk money or review run is still to reach this row */
  busy: '' | 'queued' | 'approve' | 'reject' | 'bonus'
  completedBusy: boolean
  /** the experiment's participant qualification on MTurk ('na' for HITs not from Breadboard) */
  qual: 'idle' | 'loading' | 'granted' | 'none' | 'error' | 'na'
  qualBusy: boolean
  /** the last action's error, shown on the row */
  error: string | null
  /** answers expanded */
  open: boolean
}

export function toRow (a: MturkAssignment): AssignmentRow {
  const answers = parseAnswers(a.answer)
  const asked = bonusFromAnswers(answers.values)
  return {
    id: a.assignmentId,
    workerId: a.workerId,
    approvalTime: a.approvalTime || null,
    rejectionTime: a.rejectionTime || null,
    answers,
    bonusAsked: asked,
    bonusInput: asked != null ? moneyString(asked) : '',
    completed: !!a.assignmentCompleted,
    busy: '',
    completedBusy: false,
    qual: 'idle',
    qualBusy: false,
    error: null,
    open: false,
  }
}

export type RowStatus = 'approved' | 'rejected' | 'submitted'

export function rowStatus (r: Pick<AssignmentRow, 'approvalTime' | 'rejectionTime'>): RowStatus {
  if (r.approvalTime) return 'approved'
  if (r.rejectionTime) return 'rejected'
  return 'submitted'
}

/** Now, in the server's minute format ("2026-10-27T14:05Z"). */
export function nowMinute (): string {
  return new Date().toISOString().slice(0, 16) + 'Z'
}

export function plural (n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`
}
