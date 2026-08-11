import type { PredictionOutcome } from '@/lib/api-types'

export type GameStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED'

export type AdminSummaryResponse = {
  date: string
  totalGameCount: number
  scheduledGameCount: number
  inProgressGameCount: number
  finishedGameCount: number
  cancelledGameCount: number
  systemPredictionCount: number
  shadowPredictionGameCount: number
  pendingUserPredictionCount: number
  productionModelVersion: string
  shadowModelVersion: string
  shadowArtifactSha256: string
}

export type AdminGameResponse = {
  id: number
  gameDate: string
  gameTime: string
  homeTeamName: string
  awayTeamName: string
  stadium: string
  status: GameStatus
  homeScore: number | null
  awayScore: number | null
  predictionCloseAt: string | null
  aiPrediction: {
    modelVersion: string
    generatedAt: string
  } | null
  userOdds: {
    finalized: boolean
    finalizedAt: string | null
  } | null
}

export type GameSyncResponse = {
  targetDate: string
  sourceRowCount: number
  collectedGameCount: number
  insertedCount: number
  updatedCount: number
  statusChangedCount: number
  finishedCount: number
  cancelledCount: number
  settlementSuccessCount: number
  failedCount: number
  errors: string[]
  startedAt: string
  finishedAt: string
}

export type TeamStatsSyncResponse = {
  statDate: string
  sourceTeamCount: number
  insertedCount: number
  updatedCount: number
  failedCount: number
  errors: string[]
}

export type StartingPitcherSyncResponse = {
  gameDate: string
  sourceGameCount: number
  collectedPitcherCount: number
  insertedCount: number
  updatedCount: number
  pitcherStatSavedCount: number
  failedCount: number
  errors: string[]
}

export type PredictionGenerationResponse = {
  gameId: number
  status: string
  predictedOutcome: PredictionOutcome | null
  modelVersion: string | null
  message: string
}

export type PredictionGenerationBatchResponse = {
  date: string
  targetCount: number
  createdCount: number
  updatedCount: number
  skippedCount: number
  failedCount: number
  results: PredictionGenerationResponse[]
}

export type ShadowModelMetrics = {
  modelVersion: string
  evaluatedGameCount: number
  accuracy: number
  logLoss: number
  brierScore: number
  macroF1: number
  averageMaxProbability: number
}

export type ShadowEvaluationResponse = {
  from: string
  to: string
  commonEvaluatedGameCount: number
  featureSnapshotMismatchCount: number
  artifactMismatchCount: number
  baseline: ShadowModelMetrics
  logistic: ShadowModelMetrics
  predictedOutcomeAgreementRate: number
  logisticCorrectBaselineWrongCount: number
  baselineCorrectLogisticWrongCount: number
  bothCorrectCount: number
  bothWrongCount: number
}

export type ModelPredictionView = {
  modelVersion: string
  stage: string
  homeWinProbability: number
  drawProbability: number
  awayWinProbability: number
  predictedOutcome: PredictionOutcome
  artifactSha256: string | null
  generatedAt: string
  featureSnapshotId: number | null
  featureAsOf: string | null
}

export type GameModelComparisonResponse = {
  gameId: number
  gameDate: string
  gameTime: string
  homeTeamName: string
  awayTeamName: string
  gameStatus: GameStatus
  actualResult: PredictionOutcome | null
  sameFeatureSnapshot: boolean
  baseline: ModelPredictionView | null
  logistic: ModelPredictionView | null
}

export type BackfillResponse = {
  from: string
  to: string
  gameSync: {
    requested: boolean
    requestedMonthCount: number
    successfulMonthCount: number
    failedMonthCount: number
    insertedGameCount: number
    updatedGameCount: number
    errors: string[]
  }
  finishedGameCount: number
  snapshotCreatedCount: number
  snapshotExistingCount: number
  historyCreatedCount: number
  historyExistingCount: number
  failedGameCount: number
  errors: string[]
}

export type HistoricalEvaluationResponse = {
  modelVersion: string
  from: string
  to: string
  finishedGameCount: number
  featureGeneratedGameCount: number
  evaluableGameCount: number
  dataCoverage: number
  overallAccuracy: number
  logLoss: number
  brierScore: number
}
