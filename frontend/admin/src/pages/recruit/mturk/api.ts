/**
 * The server's Mechanical Turk routes (/amtadmin/*, controllers/AMTAdmin.java), bound to one mode.
 *
 * A client is made for a mode and keeps it: the tab contents are rebuilt when the researcher
 * switches mode, so a bulk payment that is still running finishes on the site it started on
 * instead of continuing on the other one.
 */
import { ref } from 'vue'
import { http } from '@/lib/http'
import { describeError } from './lib'
import type { MturkMode } from './mode'

/** Set once any call reports that the server has no AWS keys. */
export const noKeys = ref(false)

export { activeJobs, track } from './jobs'

/** Bumped after anything that moves money, so the balance in the header reloads. */
export const balanceTick = ref(0)
export function balanceChanged () { balanceTick.value++ }

// ---- shapes ---------------------------------------------------------------------------------------

/** An MTurk HIT as the AWS SDK bean is serialised (Jackson lower-cases the "HIT" prefixes). */
export interface MturkHit {
  hitid?: string
  hitId?: string
  title: string
  description?: string
  keywords?: string
  /** epoch ms */
  creationTime?: number
  expiration?: number
  reward?: string
  maxAssignments?: number
  numberOfAssignmentsAvailable?: number
  numberOfAssignmentsCompleted?: number
  numberOfAssignmentsPending?: number
  requesterAnnotation?: string
  hitstatus?: string
  [key: string]: any
}

export function hitIdOf (h: MturkHit): string {
  return h.hitid || h.hitId || (h as any).HITId || ''
}

export interface MturkAssignment {
  assignmentId: string
  workerId: string
  /** "yyyy-MM-dd'T'HH:mm'Z'" or null */
  approvalTime: string | null
  rejectionTime: string | null
  /** QuestionFormAnswers XML */
  answer: string | null
  assignmentCompleted: boolean
}

export interface BonusPayment {
  workerId: string
  bonusAmount: string
  assignmentId: string
  reason: string
  /** epoch ms */
  grantTime: number
}

export interface QualificationScore {
  status: 'Granted' | 'Revoked' | 'None' | string
  integerValue?: number
  grantTime?: string
}

export interface QualificationTypeRow {
  label?: string
  experimentUid: string
  experimentName: string
  creationTime: string | null
  description: string | null
  qualificationTypeId: string | null
  qualificationTypeStatus: string | null
  name?: string | null
}

export interface QualifiedWorker {
  qualificationTypeId: string
  grantTime: string
  integerValue: number
  status: string
  workerId: string
}

export interface WorkerAssignment {
  id: number
  assignmentId: string
  workerId: string
  assignmentStatus: string | null
  submitTime: string | null
  reason: string | null
  assignmentCompleted: boolean
  bonusGranted?: boolean
  bonusAmount?: string | null
  experimentName: string
  experimentUid: string
  experimentId: number
}

export interface WorkerRow {
  id: string
  nAssignments: number
  assignmentsCompleted: number
  assignments: WorkerAssignment[]
}

/** A HIT Breadboard created (AMTHit.toJson), as it appears in a session's `hits`. */
export interface CreatedHit {
  id: number
  creationTime: string
  hitId: string
  title: string
  description: string
  lifetimeInSeconds: string
  tutorialTime: string
  maxAssignments: string
  externalURL: string
  reward: string
  disallowPrevious: 'type' | 'any' | 'none' | string
  sandbox: boolean
  extended?: boolean
}

export interface CreateHitBody {
  title: string
  description: string
  reward: string
  maxAssignments: number
  hitLifetime: number
  assignmentDuration: number
  keywords: string
  tutorialTime: number
  disallowPrevious: 'type' | 'any' | 'none'
  experimentId: string
  experimentInstanceId: string
  // last: the server looks fields up anywhere in the body, so nothing inside may shadow them
  qualificationRequirements: unknown[]
}

// ---- client -------------------------------------------------------------------------------------

function qs (params: Record<string, string | number | boolean | null | undefined>): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v == null) continue
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

const seg = encodeURIComponent

const typeCache = new Map<string, Promise<string>>()
/** Forget looked-up qualification types (after one is created on the Qualifications tab). */
export function forgetQualificationType (mode: MturkMode, experimentUid: string) {
  typeCache.delete(`${mode}:${experimentUid}`)
}

export function mturkApi (mode: MturkMode) {
  const sandbox = mode === 'sandbox'

  async function call<T> (run: () => Promise<T>): Promise<T> {
    try {
      return await run()
    } catch (e) {
      if (describeError(e).noKeys) noKeys.value = true
      throw e
    }
  }

  return {
    mode,
    sandbox,

    balance () {
      return call(() => http.get<{ availableBalance: string, onHoldBalance: string | null }>(`/amtadmin/getAccountBalance${qs({ sandbox })}`))
    },

    listHits (nextToken: string | null, maxResults = 20) {
      return call(() => http.get<{ hits: MturkHit[], nextToken: string | null }>(`/amtadmin/listHITs${qs({ sandbox, maxResults, nextToken })}`))
    },

    listAssignments (hitId: string, nextToken: string | null, maxResults = 50) {
      return call(() => http.get<{ assignments: MturkAssignment[], nextToken: string | null }>(
        `/amtadmin/listAssignmentsForHIT/${seg(hitId)}${qs({ sandbox, maxResults, nextToken })}`))
    },

    listBonusPayments (hitId: string, nextToken: string | null, maxResults = 100) {
      return call(() => http.get<{ bonusPayments: BonusPayment[], nextToken: string | null }>(
        `/amtadmin/listBonusPaymentsForHIT/${seg(hitId)}${qs({ sandbox, maxResults, nextToken })}`))
    },

    approve (assignmentId: string) {
      // A POST: approving pays the worker, so a link elsewhere must not be able to do it.
      return call(() => http.post(`/amtadmin/approveAssignment/${seg(assignmentId)}${qs({ sandbox })}`))
    },

    reject (assignmentId: string, requesterFeedback: string) {
      return call(() => http.post(`/amtadmin/rejectAssignment/${seg(assignmentId)}${qs({ sandbox })}`, { requesterFeedback }))
    },

    /**
     * `bonusAmount` is a string such as "1.50" (the server refuses numbers). With the same
     * `uniqueRequestToken`, MTurk pays a retried bonus only once: `duplicate` says it had already.
     */
    sendBonus (assignmentId: string, workerId: string, bonusAmount: string, reason: string, uniqueRequestToken?: string) {
      const body: Record<string, string> = { workerId, bonusAmount, reason }
      if (uniqueRequestToken) body.uniqueRequestToken = uniqueRequestToken
      return call(() => http.post<{ sent?: boolean, duplicate?: boolean } | null>(`/amtadmin/sendBonus/${seg(assignmentId)}${qs({ sandbox })}`, body))
    },

    /** Local bookkeeping only (no MTurk call), so it has no mode. */
    setCompleted (assignmentId: string, completed: boolean) {
      return call(() => http.post(`/amtadmin/updateAssignmentCompleted/${seg(assignmentId)}`, { completed }))
    },

    createHit (body: CreateHitBody) {
      return call(() => http.post<CreatedHit>(`/amtadmin/createHIT${qs({ sandbox })}`, body))
    },

    createPaymentHit (workerId: string, reward: string, reason: string) {
      return call(() => http.post(`/amtadmin/createDummyHit${qs({ sandbox })}`, { workerId, reward, reason }))
    },

    /** The server lists workers of every experiment; the id in the path is required but unused. */
    workers (experimentId: number, opts: { limit: number, offset: number, search: string }) {
      return call(() => http.get<{ total: number, offset: number, limit: number, amtWorkers: WorkerRow[] }>(
        `/amtadmin/getAMTWorkers/${seg(String(experimentId))}${qs({ sandbox, limit: opts.limit, offset: opts.offset, search: opts.search })}`))
    },

    /** Record worker ids as past participants of an experiment (one id per line). */
    importWorkers (experimentId: number, ids: string[]) {
      const form = new FormData()
      form.append('file', new Blob([ids.join('\n') + '\n'], { type: 'text/csv' }), 'workers.csv')
      return call(() => http.post(`/amtadmin/importAMTWorkers/${seg(String(experimentId))}${qs({ sandbox })}`, form))
    },

    grantQualification (workerId: string, qualificationTypeId: string) {
      return call(() => http.post(`/amtadmin/assignParticipantQualification${qs({ sandbox })}`, { workerId, qualificationTypeId }))
    },

    revokeQualification (workerId: string, qualificationTypeId: string) {
      return call(() => http.post(`/amtadmin/removeParticipantQualification${qs({ sandbox })}`, { workerId, qualificationTypeId }))
    },

    qualificationScore (workerId: string, qualificationTypeId: string) {
      return call(() => http.post<QualificationScore>(`/amtadmin/getQualificationScore${qs({ sandbox })}`, { workerId, qualificationTypeId }))
    },

    /**
     * An experiment's participant qualification type id. The server creates the type on MTurk if
     * it is missing, so this is only ever asked for real experiment uids (the old console asked
     * for "undefined" on non-Breadboard HITs and created a junk type). Remembered per mode.
     */
    experimentTypeId (experimentUid: string): Promise<string> {
      const key = `${mode}:${experimentUid}`
      let p = typeCache.get(key)
      if (!p) {
        // A POST: it creates the type on the Mechanical Turk account when there is none yet.
        p = call(() => http.post<{ qualificationTypeId: string }>(`/amtadmin/getExperimentQualificationTypeId/${seg(experimentUid)}${qs({ sandbox })}`))
          .then((r) => {
            if (!r || !r.qualificationTypeId) throw new Error('MTurk did not return a qualification type.')
            return r.qualificationTypeId
          })
        p.catch(() => typeCache.delete(key))
        typeCache.set(key, p)
      }
      return p
    },

    workersWithQualification (qualificationTypeId: string, nextToken: string | null, maxResults: number) {
      return call(() => http.get<{ qualifications: QualifiedWorker[], nextToken: string | null }>(
        `/amtadmin/listWorkersWithQualificationType/${seg(qualificationTypeId)}${qs({ sandbox, maxResults, nextToken })}`))
    },

    removeWorkerQualification (qualificationTypeId: string, workerId: string) {
      return call(() => http.post(`/amtadmin/disassociateQualificationFromWorker/${seg(qualificationTypeId)}${qs({ sandbox })}`, { workerId }))
    },

    /** One row per experiment on the server; makes one MTurk call per experiment, so it is slow. */
    qualificationTypes () {
      return call(() => http.get<{ qualificationTypes: QualificationTypeRow[] }>(`/amtadmin/listQualificationTypes${qs({ sandbox })}`))
    },

    addQualificationType (experimentUid: string) {
      return call(() => http.put<QualificationTypeRow>(`/amtadmin/addQualificationType/${seg(experimentUid)}${qs({ sandbox })}`))
    },
  }
}

export type MturkApi = ReturnType<typeof mturkApi>
