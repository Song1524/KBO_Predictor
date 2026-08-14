export type PredictionOutcome = 'HOME_WIN' | 'DRAW' | 'AWAY_WIN'

export type PredictionSettlementStatus =
  | 'PENDING'
  | 'WON'
  | 'LOST'
  | 'REFUNDED'

export type PointHistoryType =
  | 'PREDICTION_BET'
  | 'PREDICTION_REWARD'
  | 'GAME_CANCEL_REFUND'
  | 'SIGNUP_BONUS'

export type TeamApiResponse = {
  id: number
  kboTeamCode: string
  name: string
  shortName: string
  primaryColor: string
  secondaryColor: string
}

export type TeamStandingApiResponse = {
  rank: number
  teamId: number
  teamName: string
  games: number
  wins: number
  losses: number
  draws: number
  winRate: number | null
  gamesBehind: number | null
  streak: string | null
  statDate: string
  collectedAt: string
}

export type SignupRequest = {
  email: string
  password: string
  nickname: string
  favoriteTeamId: number | null
}

export type UserApiResponse = {
  id: number
  email: string
  nickname: string
  favoriteTeamId: number | null
  favoriteTeamName: string | null
  point: number
  role: string
  status: string
}

export type UserPredictionApiResponse = {
  id: number
  userId: number
  nickname: string
  gameId: number
  gameDate: string
  homeTeamName: string
  awayTeamName: string
  selectedOutcome: PredictionOutcome
  pointAmount: number
  isCorrect: boolean | null
  settled: boolean
  settlementStatus: PredictionSettlementStatus
  createdAt: string
  updatedAt: string
}

export type PointHistoryApiResponse = {
  id: number
  type: PointHistoryType
  pointChange: number
  balanceAfter: number
  gameId: number | null
  userPredictionId: number | null
  description: string
  createdAt: string
}
