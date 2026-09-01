import type { PredictionOutcome } from '@/lib/api-types'

export type GameStatus =
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'CANCELLED'

export type OutcomeOddsApiResponse = {
  outcome: PredictionOutcome
  betPoints: number | null
  userBettingRate: number | null
  odds: number | null
}

export type GameOddsApiResponse = {
  gameId: number
  totalBetPoints: number | null
  homeWin: OutcomeOddsApiResponse | null
  draw: OutcomeOddsApiResponse | null
  awayWin: OutcomeOddsApiResponse | null
  bettingOpen: boolean | null
  finalized: boolean | null
  predictionCloseAt: string | null
  finalizedAt: string | null
}

export type SystemPredictionApiResponse = {
  id: number
  gameId: number
  predictedWinnerTeamId: number | null
  predictedWinnerTeamName: string | null
  predictedOutcome: PredictionOutcome | null
  homeWinProbability: number | null
  drawProbability: number | null
  awayWinProbability: number | null
  homeScorePoint: number | null
  awayScorePoint: number | null
  reason: string | null
  modelVersion: string | null
  featureCoverage: number | null
  createdAt: string | null
  generatedAt: string | null
}

export type GameApiResponse = {
  id: number
  externalGameId: string | null
  season: number
  gameDate: string
  gameTime: string | null
  homeTeamId: number | null
  homeTeamName: string | null
  awayTeamId: number | null
  awayTeamName: string | null
  homeStartingPitcherPlayerId: number | null
  homeStartingPitcherName: string | null
  awayStartingPitcherPlayerId: number | null
  awayStartingPitcherName: string | null
  stadium: string | null
  status: GameStatus | null
  homeScore: number | null
  awayScore: number | null
  winnerTeamId: number | null
  result: PredictionOutcome | null
  predictionCloseAt: string | null
  cancelReason: string | null
  aiPrediction: SystemPredictionApiResponse | null
  userOdds: GameOddsApiResponse | null
}

export type TeamStatApiResponse = {
  id: number
  teamId: number
  teamName: string
  season: number
  statDate: string
  wins: number | null
  losses: number | null
  draws: number | null
  winRate: number | null
  recent10Wins: number | null
  recent10Losses: number | null
  recent10Draws: number | null
  homeWins: number | null
  homeLosses: number | null
  homeDraws: number | null
  awayWins: number | null
  awayLosses: number | null
  awayDraws: number | null
  recent5WinRate: number | null
  recent10WinRate: number | null
  recent5AvgRuns: number | null
  recent5AvgRunsAllowed: number | null
  recent10AvgRuns: number | null
  recent10AvgRunsAllowed: number | null
  battingAverage: number | null
  era: number | null
  collectedAt: string | null
}
