import { useEffect, useState } from 'react'
import type { TeamStandingApiResponse } from '@/lib/api-types'

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isStanding(value: unknown): value is TeamStandingApiResponse {
  if (typeof value !== 'object' || value === null) return false

  const standing = value as Record<string, unknown>
  return (
    isFiniteNumber(standing.rank) &&
    isFiniteNumber(standing.teamId) &&
    typeof standing.teamName === 'string' &&
    isFiniteNumber(standing.games) &&
    isFiniteNumber(standing.wins) &&
    isFiniteNumber(standing.losses) &&
    isFiniteNumber(standing.draws) &&
    (standing.winRate === null || isFiniteNumber(standing.winRate)) &&
    (standing.gamesBehind === null || isFiniteNumber(standing.gamesBehind)) &&
    (standing.streak === null || typeof standing.streak === 'string') &&
    typeof standing.statDate === 'string' &&
    typeof standing.collectedAt === 'string'
  )
}

export function useStandings() {
  const [standings, setStandings] = useState<TeamStandingApiResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()

    const load = async () => {
      try {
        setIsLoading(true)
        setError('')

        const response = await fetch('/api/standings', {
          signal: controller.signal,
        })
        if (!response.ok) {
          throw new Error('KBO 순위를 불러오지 못했습니다.')
        }

        const data: unknown = await response.json()
        if (!Array.isArray(data) || !data.every(isStanding)) {
          throw new Error('KBO 순위 응답 형식이 올바르지 않습니다.')
        }

        setStandings(
          [...data].sort((left, right) => left.rank - right.rank),
        )
      } catch (loadError) {
        if (controller.signal.aborted) return
        console.error(loadError)
        setStandings([])
        setError('공식 KBO 순위를 불러오지 못했습니다.')
      } finally {
        if (!controller.signal.aborted) setIsLoading(false)
      }
    }

    void load()
    return () => controller.abort()
  }, [reloadKey])

  return {
    standings,
    isLoading,
    error,
    reload: () => setReloadKey((current) => current + 1),
  }
}
