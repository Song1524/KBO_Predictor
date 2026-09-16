import type {
  GameResultCorrectionRequest,
  GameResultCorrectionResponse,
  GameSettlementStatusResponse,
  PredictionSettlementResponse,
  PredictionSettlementRollbackResponse,
  SettlementRollbackRequest,
} from '@/lib/admin-api-types'
import { apiFetch } from '@/lib/api-client'

function errorMessage(body: unknown, fallback: string) {
  if (typeof body !== 'object' || body === null) return fallback

  const value = body as Record<string, unknown>
  if (typeof value.message === 'string' && value.message.trim()) {
    return value.message
  }
  if (typeof value.detail === 'string' && value.detail.trim()) {
    return value.detail
  }
  return fallback
}

async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(url, {
    credentials: 'include',
    ...init,
    headers: init?.body
      ? { 'Content-Type': 'application/json', ...init.headers }
      : init?.headers,
  })
  const body: unknown = await response.json().catch(() => null)

  if (!response.ok) {
    const fallback = (() => {
      switch (response.status) {
        case 400:
          return '요청 값과 경기 상태를 확인해 주세요.'
        case 401:
          return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
        case 403:
          return '관리자 권한이 필요한 작업입니다.'
        case 404:
          return '경기 또는 정산 회차를 찾을 수 없습니다.'
        case 409:
          return '현재 정산 상태와 충돌하여 작업을 처리할 수 없습니다.'
        default:
          return '서버에서 정산 관리 작업을 처리하지 못했습니다.'
      }
    })()
    throw new Error(errorMessage(body, fallback))
  }

  return body as T
}

export function getGameSettlementStatus(gameId: number, signal?: AbortSignal) {
  return requestJson<GameSettlementStatusResponse>(
    `/api/admin/games/${gameId}/settlement/status`,
    { signal },
  )
}

export function settleGame(gameId: number, rollbackRevision?: number) {
  const query = rollbackRevision == null
    ? ''
    : `?rollbackRevision=${encodeURIComponent(rollbackRevision)}`
  return requestJson<PredictionSettlementResponse>(
    `/api/admin/games/${gameId}/settlement${query}`,
    { method: 'POST' },
  )
}

export function rollbackGameSettlement(
  gameId: number,
  request: SettlementRollbackRequest,
) {
  return requestJson<PredictionSettlementRollbackResponse>(
    `/api/admin/games/${gameId}/settlement/rollback`,
    { method: 'POST', body: JSON.stringify(request) },
  )
}

export function correctGameResult(
  gameId: number,
  request: GameResultCorrectionRequest,
) {
  return requestJson<GameResultCorrectionResponse>(
    `/api/admin/games/${gameId}/result`,
    { method: 'PUT', body: JSON.stringify(request) },
  )
}
