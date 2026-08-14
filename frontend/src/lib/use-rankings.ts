import { useEffect, useState } from 'react'
import type {
  RankingApiResponse,
  RankingEntryApiResponse,
  RankingType,
} from '@/lib/api-types'

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || isFiniteNumber(value)
}

function isRankingEntry(value: unknown): value is RankingEntryApiResponse {
  if (typeof value !== 'object' || value === null) return false
  const entry = value as Record<string, unknown>
  return (
    isFiniteNumber(entry.rank) &&
    isFiniteNumber(entry.userId) &&
    typeof entry.nickname === 'string' &&
    isNullableNumber(entry.currentPoint) &&
    isNullableNumber(entry.periodProfit) &&
    isFiniteNumber(entry.predictionCount) &&
    isFiniteNumber(entry.correctCount) &&
    isNullableNumber(entry.hitRate)
  )
}

function isRankingResponse(
  value: unknown,
  requestedType: RankingType,
): value is RankingApiResponse {
  if (typeof value !== 'object' || value === null) return false
  const response = value as Record<string, unknown>
  return (
    response.type === requestedType &&
    (response.periodStart === null || typeof response.periodStart === 'string') &&
    (response.periodEndExclusive === null ||
      typeof response.periodEndExclusive === 'string') &&
    Array.isArray(response.rankings) &&
    response.rankings.every(isRankingEntry) &&
    (response.myRanking === null || isRankingEntry(response.myRanking))
  )
}

export function useRankings(
  type: RankingType,
  enabled: boolean,
  viewerId: number | null,
) {
  const [data, setData] = useState<RankingApiResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()

    const load = async () => {
      try {
        setIsLoading(true)
        setError('')
        setData(null)

        const response = await fetch(
          `/api/rankings?type=${encodeURIComponent(type)}&limit=20`,
          {
            credentials: 'include',
            signal: controller.signal,
          },
        )
        if (!response.ok) {
          throw new Error('PLAYBALL 랭킹을 불러오지 못했습니다.')
        }

        const responseBody: unknown = await response.json()
        if (!isRankingResponse(responseBody, type)) {
          throw new Error('PLAYBALL 랭킹 응답 형식이 올바르지 않습니다.')
        }
        setData(responseBody)
      } catch (loadError) {
        if (controller.signal.aborted) return
        console.error(loadError)
        setError('PLAYBALL 랭킹을 불러오지 못했습니다.')
      } finally {
        if (!controller.signal.aborted) setIsLoading(false)
      }
    }

    void load()
    return () => controller.abort()
  }, [enabled, reloadKey, type, viewerId])

  return {
    data,
    isLoading,
    error,
    reload: () => setReloadKey((current) => current + 1),
  }
}
