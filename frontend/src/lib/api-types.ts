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
  | 'DAILY_LOGIN_BONUS'

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

export type RankingType =
  | 'TOTAL_POINT'
  | 'MONTHLY_PROFIT'
  | 'WEEKLY_PROFIT'

export type RankingEntryApiResponse = {
  rank: number
  userId: number
  nickname: string
  currentPoint: number | null
  periodProfit: number | null
  predictionCount: number
  correctCount: number
  hitRate: number | null
}

export type RankingApiResponse = {
  type: RankingType
  periodStart: string | null
  periodEndExclusive: string | null
  rankings: RankingEntryApiResponse[]
  myRanking: RankingEntryApiResponse | null
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

export type LoginApiResponse = UserApiResponse & {
  dailyLoginBonusGranted: boolean
  dailyLoginBonusPoints: number
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

export type CommunityPostListItemApiResponse = {
  id: number
  title: string
  authorId: number
  authorNickname: string
  viewCount: number
  commentCount: number
  createdAt: string
  updatedAt: string
}

export type CommunityPostApiResponse = CommunityPostListItemApiResponse & {
  content: string
}

export type CommunityCommentApiResponse = {
  id: number
  postId: number
  authorId: number
  authorNickname: string
  content: string
  createdAt: string
  updatedAt: string
}

export type CommunityPageApiResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
