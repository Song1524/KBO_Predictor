import { useEffect, useState } from 'react'
import { useAuth } from '@/auth-context'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { apiFetch } from '@/lib/api-client'
import type {
  PredictionOutcome,
  UserPredictionApiResponse,
} from '@/lib/api-types'
import type {
  GameOddsApiResponse,
  GameStatus,
  OutcomeOddsApiResponse,
} from '@/lib/game-api-types'

const MIN_PREDICTION_POINTS = 100
const PREDICTION_POINT_UNIT = 100
const QUICK_POINT_AMOUNTS = [100, 300, 500] as const

export type PredictionGame = {
  id: number
  home: string
  away: string
  status: GameStatus | null
  predictionCloseAt: string | null
  userOdds: GameOddsApiResponse | null
}

type GamePredictionPanelProps = {
  game: PredictionGame
  existingPrediction: UserPredictionApiResponse | null
  isExistingPredictionLoading?: boolean
  onPredictionCreated: (prediction: UserPredictionApiResponse) => void
}

export function getOutcomeLabel(
  outcome: PredictionOutcome,
  game: Pick<PredictionGame, 'home' | 'away'>,
) {
  switch (outcome) {
    case 'HOME_WIN':
      return `${game.home} 승`
    case 'DRAW':
      return '무승부'
    case 'AWAY_WIN':
      return `${game.away} 승`
  }
}

export function formatOdds(value: number | null) {
  return value == null || !Number.isFinite(Number(value))
    ? '-'
    : `${Number(value).toFixed(2)}배`
}

export function formatPredictionCloseAt(value: string | null) {
  if (!value) return '마감 시각 미정'
  return value.replace('T', ' ').slice(0, 16)
}

function parsePredictionCloseAt(value: string | null) {
  if (!value) return null
  const hasOffset = /(Z|[+-]\d{2}:\d{2})$/i.test(value)
  const timestamp = Date.parse(hasOffset ? value : `${value}+09:00`)
  return Number.isFinite(timestamp) ? timestamp : null
}

function usePredictionDeadlineReached(value: string | null) {
  const [deadlineReached, setDeadlineReached] = useState(() => {
    const deadline = parsePredictionCloseAt(value)
    return deadline != null && Date.now() >= deadline
  })

  useEffect(() => {
    const deadline = parsePredictionCloseAt(value)
    if (deadline == null) {
      setDeadlineReached(false)
      return
    }

    let timer: number | undefined
    const updateDeadline = () => {
      const remaining = deadline - Date.now()
      setDeadlineReached(remaining <= 0)
      if (remaining > 0) {
        timer = window.setTimeout(
          updateDeadline,
          Math.min(remaining, 2_147_483_647),
        )
      }
    }

    updateDeadline()
    return () => {
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [value])

  return deadlineReached
}

function getOutcomeOdds(
  game: PredictionGame,
  outcome: PredictionOutcome,
) {
  switch (outcome) {
    case 'HOME_WIN':
      return game.userOdds?.homeWin ?? null
    case 'DRAW':
      return game.userOdds?.draw ?? null
    case 'AWAY_WIN':
      return game.userOdds?.awayWin ?? null
  }
}

export function GamePredictionPanel({
  game,
  existingPrediction,
  isExistingPredictionLoading = false,
  onPredictionCreated,
}: GamePredictionPanelProps) {
  const { user, isLoading: isAuthLoading } = useAuth()
  const [pick, setPick] = useState<PredictionOutcome | null>(null)
  const [pointAmount, setPointAmount] = useState(MIN_PREDICTION_POINTS)
  const [submittedPrediction, setSubmittedPrediction] =
    useState<UserPredictionApiResponse | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [predictionMessage, setPredictionMessage] = useState('')
  const [hasPredictionError, setHasPredictionError] = useState(false)
  const confirmedPrediction = existingPrediction ?? submittedPrediction
  const predictionCloseAt =
    game.userOdds?.predictionCloseAt ?? game.predictionCloseAt
  const deadlineReached = usePredictionDeadlineReached(predictionCloseAt)
  const bettingOpen =
    game.userOdds?.bettingOpen === true && !deadlineReached

  useEffect(() => {
    if (existingPrediction) {
      setSubmittedPrediction(null)
      setPick(existingPrediction.selectedOutcome)
      setPointAmount(existingPrediction.pointAmount)
      setHasPredictionError(false)
      setPredictionMessage(
        `${getOutcomeLabel(existingPrediction.selectedOutcome, game)}에 이미 예측했습니다.`,
      )
      return
    }

    setSubmittedPrediction(null)
    setPick(null)
    setPointAmount(MIN_PREDICTION_POINTS)
    setPredictionMessage('')
    setHasPredictionError(false)
  }, [
    existingPrediction?.id,
    existingPrediction?.pointAmount,
    existingPrediction?.selectedOutcome,
    game.away,
    game.home,
    game.id,
    user?.id,
  ])

  const requestLogin = () => {
    setHasPredictionError(true)
    setPredictionMessage('로그인 후 예측할 수 있습니다. 로그인해 주세요.')
    window.dispatchEvent(new Event('playball:open-login'))
  }

  const selectOutcome = (outcome: PredictionOutcome) => {
    if (!user) {
      requestLogin()
      return
    }
    if (confirmedPrediction) return

    setPick(outcome)
    setPredictionMessage('')
    setHasPredictionError(false)
  }

  const pointAmountIsValid =
    Number.isInteger(pointAmount) &&
    pointAmount >= MIN_PREDICTION_POINTS &&
    pointAmount % PREDICTION_POINT_UNIT === 0
  const hasEnoughPoints = user != null && user.point >= pointAmount
  const selectedOdds = pick ? getOutcomeOdds(game, pick) : null
  const canSubmit =
    pick != null &&
    user != null &&
    bettingOpen &&
    !confirmedPrediction &&
    !isSubmitting &&
    pointAmountIsValid &&
    hasEnoughPoints

  const submitPrediction = async () => {
    if (!user) {
      requestLogin()
      return
    }
    if (!pick || !canSubmit) return

    try {
      setIsSubmitting(true)
      setPredictionMessage('')
      setHasPredictionError(false)

      const response = await apiFetch('/api/user-predictions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          gameId: game.id,
          selectedOutcome: pick,
          pointAmount,
        }),
        credentials: 'include',
      })

      const responseBody = await response.json().catch(() => null)

      if (!response.ok) {
        if (response.status === 401) requestLogin()
        throw new Error(
          responseBody?.detail ??
          responseBody?.message ??
          '승부예측 등록에 실패했습니다.',
        )
      }

      const createdPrediction = responseBody as UserPredictionApiResponse
      setSubmittedPrediction(createdPrediction)
      setPick(createdPrediction.selectedOutcome)
      setPointAmount(createdPrediction.pointAmount)
      setPredictionMessage(
        `${getOutcomeLabel(createdPrediction.selectedOutcome, game)}에 ${createdPrediction.pointAmount.toLocaleString()}P를 예측했습니다.`,
      )
      onPredictionCreated(createdPrediction)
    } catch (error) {
      setHasPredictionError(true)
      setPredictionMessage(
        error instanceof Error
          ? error.message
          : '승부예측 등록 중 오류가 발생했습니다.',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  const outcomeOptions: Array<{
    outcome: PredictionOutcome
    data: OutcomeOddsApiResponse | null
  }> | null = game.userOdds
    ? [
        { outcome: 'AWAY_WIN', data: game.userOdds.awayWin },
        { outcome: 'DRAW', data: game.userOdds.draw },
        { outcome: 'HOME_WIN', data: game.userOdds.homeWin },
      ]
    : null
  const bettingStatusLabel = !game.userOdds
    ? null
    : game.userOdds.finalized
      ? '최종 배당'
      : bettingOpen
        ? '배당 변동 중'
        : deadlineReached
          ? '배당 확정 중'
          : '예측 참여 불가'

  return (
    <div className="flex flex-col gap-4">
      {outcomeOptions ? (
        <div className="grid grid-cols-3 gap-2">
          {outcomeOptions.map(({ outcome, data }) => (
            <Button
              key={outcome}
              className={`h-auto min-h-16 flex-col gap-0.5 px-2 py-2.5 disabled:border-border/40 disabled:bg-muted/50 disabled:text-muted-foreground disabled:shadow-none ${
                pick === outcome
                  ? 'shadow-sm'
                  : 'border-primary/25 shadow-sm hover:border-primary hover:bg-primary/5'
              }`}
              disabled={
                !bettingOpen ||
                !data ||
                isSubmitting ||
                confirmedPrediction !== null ||
                isAuthLoading ||
                isExistingPredictionLoading
              }
              variant={pick === outcome ? 'default' : 'outline'}
              onClick={() => selectOutcome(outcome)}
            >
              <span className="max-w-full truncate font-bold">
                {getOutcomeLabel(outcome, game)}
              </span>
              <span
                className={`font-mono text-[11px] ${pick === outcome
                  ? 'text-primary-foreground/75'
                  : 'text-muted-foreground'}`}
              >
                {formatOdds(data?.odds ?? null)}
              </span>
            </Button>
          ))}
        </div>
      ) : (
        <p className="py-3 text-center text-xs font-medium text-muted-foreground">
          투표 정보가 없습니다.
        </p>
      )}

      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-t pt-3 text-xs">
        <span className="font-medium text-foreground/70">
          예측 마감{' '}
          <span className="font-mono">
            {formatPredictionCloseAt(predictionCloseAt)}
          </span>
        </span>
        {bettingStatusLabel && (
          <span className={bettingOpen
            ? 'font-bold text-primary'
            : 'font-semibold text-muted-foreground'}>
            {bettingStatusLabel}
          </span>
        )}
      </div>

      {pick && !confirmedPrediction && user && (
        <div className="grid gap-4 rounded-xl border bg-muted/30 p-4">
          <div>
            <p className="text-sm font-bold">예측 정보 확인</p>
            <p className="mt-1 text-xs text-muted-foreground">
              결과와 포인트를 확인한 뒤 최종 제출해 주세요.
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            {QUICK_POINT_AMOUNTS.map((amount) => (
              <Button
                key={amount}
                type="button"
                size="xs"
                variant={pointAmount === amount ? 'default' : 'outline'}
                disabled={isSubmitting || amount > user.point}
                onClick={() => setPointAmount(amount)}
              >
                {amount}P
              </Button>
            ))}
          </div>

          <div className="grid grid-cols-[auto_1fr_auto] gap-2">
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              aria-label="참여 포인트 100P 줄이기"
              disabled={
                isSubmitting || pointAmount <= MIN_PREDICTION_POINTS
              }
              onClick={() => setPointAmount((amount) =>
                Math.max(MIN_PREDICTION_POINTS, amount - PREDICTION_POINT_UNIT),
              )}
            >
              −
            </Button>
            <label className="grid gap-1 text-xs font-medium">
              참여 포인트
              <input
                className="h-9 min-w-0 rounded-md border bg-background px-3 text-right font-mono text-sm"
                type="number"
                min={MIN_PREDICTION_POINTS}
                max={user.point}
                step={PREDICTION_POINT_UNIT}
                inputMode="numeric"
                value={pointAmount}
                disabled={isSubmitting}
                onChange={(event) => setPointAmount(Number(event.target.value))}
              />
            </label>
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              aria-label="참여 포인트 100P 늘리기"
              disabled={
                isSubmitting || pointAmount + PREDICTION_POINT_UNIT > user.point
              }
              onClick={() => setPointAmount((amount) =>
                amount + PREDICTION_POINT_UNIT,
              )}
            >
              +
            </Button>
          </div>

          <dl className="grid gap-2 text-xs sm:grid-cols-2">
            <div><dt className="text-muted-foreground">선택</dt><dd className="mt-0.5 font-semibold">{getOutcomeLabel(pick, game)}</dd></div>
            <div><dt className="text-muted-foreground">참여 포인트</dt><dd className="mt-0.5 font-mono font-semibold">{Number.isFinite(pointAmount) ? pointAmount.toLocaleString() : '-'}P</dd></div>
            <div><dt className="text-muted-foreground">현재 보유 포인트</dt><dd className="mt-0.5 font-mono font-semibold">{user.point.toLocaleString()}P</dd></div>
            <div><dt className="text-muted-foreground">참여 후 예상 잔액</dt><dd className="mt-0.5 font-mono font-semibold">{pointAmountIsValid && hasEnoughPoints ? (user.point - pointAmount).toLocaleString() : '-'}P</dd></div>
            <div><dt className="text-muted-foreground">현재 배당</dt><dd className="mt-0.5 font-mono font-semibold">{formatOdds(selectedOdds?.odds ?? null)}</dd></div>
          </dl>

          {!pointAmountIsValid && (
            <p className="text-xs font-medium text-destructive">
              참여 포인트는 100P 이상, 100P 단위로 입력해 주세요.
            </p>
          )}
          {pointAmountIsValid && !hasEnoughPoints && (
            <p className="text-xs font-medium text-destructive">
              포인트가 부족합니다.
            </p>
          )}
          <p className="text-[11px] leading-relaxed text-muted-foreground">
            현재 배당은 다른 사용자의 참여에 따라 마감 전까지 변동되며,
            적중 시 지급 포인트는 마감 시 확정된 최종 배당으로 계산됩니다.
          </p>
          <Button disabled={!canSubmit} onClick={() => void submitPrediction()}>
            {isSubmitting
              ? '예측 등록 중...'
              : `${Number.isFinite(pointAmount) ? pointAmount.toLocaleString() : '-'}P로 예측하기`}
          </Button>
        </div>
      )}

      {confirmedPrediction && (
        <div className="grid gap-2 rounded-xl border border-primary/30 bg-primary/5 p-4 text-sm">
          <div className="flex items-center justify-between gap-2">
            <strong>이미 예측했습니다</strong>
            <Badge variant="outline">변경 불가</Badge>
          </div>
          <p>
            선택 <strong>{getOutcomeLabel(confirmedPrediction.selectedOutcome, game)}</strong>
            <span className="mx-2 text-muted-foreground">·</span>
            참여 <strong className="font-mono">{confirmedPrediction.pointAmount.toLocaleString()}P</strong>
          </p>
          {!game.userOdds?.bettingOpen && game.userOdds?.finalized && (
            <p className="text-xs text-muted-foreground">
              선택 결과 최종 배당 {formatOdds(getOutcomeOdds(game, confirmedPrediction.selectedOutcome)?.odds ?? null)}
            </p>
          )}
        </div>
      )}

      {predictionMessage && (
        <p className={`text-center text-xs font-medium ${hasPredictionError ? 'text-destructive' : 'text-primary'}`}>
          {predictionMessage}
        </p>
      )}
    </div>
  )
}
