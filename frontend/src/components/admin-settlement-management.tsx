import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  CircleDollarSign,
  LoaderCircle,
  PencilLine,
  RefreshCw,
  RotateCcw,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  correctGameResult,
  getGameSettlementStatus,
  rollbackGameSettlement,
  settleGame,
} from '@/lib/admin-settlement-api'
import type {
  AdminGameResponse,
  GameResultCorrectionRequest,
  GameSettlementStatusResponse,
  GameStatus,
} from '@/lib/admin-api-types'
import type { PredictionOutcome } from '@/lib/api-types'

type CorrectionStatus = Extract<GameStatus, 'FINISHED' | 'CANCELLED'>
type SettlementAction = 'settle' | 'rollback' | 'correct' | 'resettle'

type Confirmation = {
  action: SettlementAction
  title: string
  description: string
  variant: 'default' | 'destructive'
  execute: () => Promise<string>
}

type Props = {
  games: AdminGameResponse[]
  selectedDate: string
  disabled: boolean
  onBusyChange: (busy: boolean) => void
  onRefreshGames: () => Promise<void>
}

const statusLabels: Record<GameStatus, string> = {
  SCHEDULED: '예정',
  IN_PROGRESS: '진행 중',
  FINISHED: '종료',
  CANCELLED: '취소',
}

const resultLabels: Record<PredictionOutcome, string> = {
  HOME_WIN: '홈 승',
  DRAW: '무승부',
  AWAY_WIN: '원정 승',
}

const sourceLabels = {
  AUTOMATIC: '자동',
  ADMIN: '관리자',
  LEGACY: '기존 데이터',
} as const

function formatDateTime(value: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatScore(awayScore: number | null, homeScore: number | null) {
  if (awayScore == null || homeScore == null) return '-'
  return `${awayScore} : ${homeScore}`
}

function formatResult(result: PredictionOutcome | null) {
  return result == null ? '-' : resultLabels[result]
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '정산 관리 작업을 처리하지 못했습니다.'
}

function parseScore(value: string) {
  if (!/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed <= 2_147_483_647
    ? parsed
    : null
}

function DetailItem({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-1 text-sm font-semibold">{value}</div>
    </div>
  )
}

export function AdminSettlementManagement({
  games,
  selectedDate,
  disabled,
  onBusyChange,
  onRefreshGames,
}: Props) {
  const [selectedGameId, setSelectedGameId] = useState<number | null>(null)
  const [status, setStatus] = useState<GameSettlementStatusResponse | null>(null)
  const [statusLoading, setStatusLoading] = useState(false)
  const [busyAction, setBusyAction] = useState<SettlementAction | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const [rollbackReason, setRollbackReason] = useState('')
  const [correctionStatus, setCorrectionStatus] =
    useState<CorrectionStatus>('FINISHED')
  const [homeScore, setHomeScore] = useState('')
  const [awayScore, setAwayScore] = useState('')
  const [cancelReason, setCancelReason] = useState('')
  const [correctionReason, setCorrectionReason] = useState('')
  const requestInFlight = useRef(false)

  const selectedGame = useMemo(
    () => games.find((game) => game.id === selectedGameId) ?? null,
    [games, selectedGameId],
  )
  const isBusy = disabled || busyAction !== null

  const setCorrectionForm = (nextStatus: GameSettlementStatusResponse) => {
    setCorrectionStatus(
      nextStatus.currentGameStatus === 'CANCELLED' ? 'CANCELLED' : 'FINISHED',
    )
    setHomeScore(nextStatus.currentHomeScore?.toString() ?? '')
    setAwayScore(nextStatus.currentAwayScore?.toString() ?? '')
    setCancelReason('')
    setCorrectionReason('')
  }

  useEffect(() => {
    if (selectedGameId != null && !games.some((game) => game.id === selectedGameId)) {
      setSelectedGameId(null)
      setStatus(null)
    }
  }, [games, selectedGameId])

  useEffect(() => {
    if (selectedGameId == null) {
      setStatus(null)
      setError('')
      setNotice('')
      return
    }

    const controller = new AbortController()
    const load = async () => {
      try {
        setStatusLoading(true)
        setError('')
        setNotice('')
        const response = await getGameSettlementStatus(
          selectedGameId,
          controller.signal,
        )
        setStatus(response)
        setCorrectionForm(response)
        setRollbackReason('')
      } catch (loadError) {
        if (controller.signal.aborted) return
        setStatus(null)
        setError(errorMessage(loadError))
      } finally {
        if (!controller.signal.aborted) setStatusLoading(false)
      }
    }

    void load()
    return () => controller.abort()
  }, [selectedGameId])

  const refreshStatus = async (gameId: number) => {
    const response = await getGameSettlementStatus(gameId)
    setStatus(response)
    setCorrectionForm(response)
    return response
  }

  const runConfirmedAction = async () => {
    if (
      !confirmation
      || isBusy
      || selectedGameId == null
      || requestInFlight.current
    ) return

    const current = confirmation
    const gameId = selectedGameId
    requestInFlight.current = true
    setConfirmation(null)
    setBusyAction(current.action)
    onBusyChange(true)
    setError('')
    setNotice('')

    try {
      const successMessage = await current.execute()
      setNotice(successMessage)

      const refreshResults = await Promise.allSettled([
        refreshStatus(gameId),
        onRefreshGames(),
      ])
      if (refreshResults.some((result) => result.status === 'rejected')) {
        setError('작업은 완료되었지만 최신 상태 일부를 다시 불러오지 못했습니다. 상태 새로고침을 실행해 주세요.')
      }
    } catch (actionError) {
      setError(errorMessage(actionError))
    } finally {
      requestInFlight.current = false
      setBusyAction(null)
      onBusyChange(false)
    }
  }

  const reloadStatus = async () => {
    if (selectedGameId == null || isBusy || statusLoading) return
    try {
      setStatusLoading(true)
      setError('')
      await refreshStatus(selectedGameId)
      setNotice('정산 상태를 최신 정보로 갱신했습니다.')
    } catch (loadError) {
      setError(errorMessage(loadError))
    } finally {
      setStatusLoading(false)
    }
  }

  const requestInitialSettlement = () => {
    if (!status || selectedGameId == null || isBusy) return
    setConfirmation({
      action: 'settle',
      title: '수동 정산 확인',
      description: `경기 #${selectedGameId}을(를) 수동 정산하시겠습니까? 서버가 경기 결과와 현재 정산 상태를 다시 검증합니다.`,
      variant: 'default',
      execute: async () => {
        const response = await settleGame(selectedGameId)
        return `정산 완료 · revision #${response.settlementRevision ?? '-'} · 대상 ${response.totalCount}건 · 적중 ${response.correctCount}건 · 실패 ${response.incorrectCount}건 · 환불 ${response.refundedCount}건 · 지급/환불 ${response.totalPaidPoints.toLocaleString()}P`
      },
    })
  }

  const requestRollback = () => {
    if (!status || selectedGameId == null || status.latestRevision == null || isBusy) return
    const reason = rollbackReason.trim()
    if (!reason) {
      setError('rollback 사유를 입력해 주세요.')
      return
    }
    if (reason.length > 255) {
      setError('rollback 사유는 255자 이하여야 합니다.')
      return
    }

    const revision = status.latestRevision
    setError('')
    setConfirmation({
      action: 'rollback',
      title: `정산 revision #${revision} rollback`,
      description: '이 정산을 롤백하면 해당 경기에서 지급/환불된 포인트가 역분개되며 사용자 잔액이 음수가 될 수 있습니다. 계속하시겠습니까?',
      variant: 'destructive',
      execute: async () => {
        const response = await rollbackGameSettlement(selectedGameId, {
          settlementRevision: revision,
          reason,
        })
        setRollbackReason('')
        if (response.alreadyRolledBack) {
          return `revision #${response.settlementRevision}은(는) 이미 rollback되어 추가 차감 없이 처리되었습니다.`
        }
        return `rollback 완료 · revision #${response.settlementRevision} · 예측 복원 ${response.restoredPredictionCount}건 · 역분개 ${response.reversedPointHistoryCount}건 / ${response.reversedPointTotal.toLocaleString()}P`
      },
    })
  }

  const buildCorrectionRequest = (): GameResultCorrectionRequest | null => {
    if (!status || status.latestRevision == null) return null
    const reason = correctionReason.trim()
    if (!reason) {
      setError('결과 정정 사유를 입력해 주세요.')
      return null
    }
    if (reason.length > 255) {
      setError('결과 정정 사유는 255자 이하여야 합니다.')
      return null
    }
    if (cancelReason.trim().length > 255) {
      setError('경기 취소 사유는 255자 이하여야 합니다.')
      return null
    }

    if (correctionStatus === 'CANCELLED') {
      return {
        settlementRevision: status.latestRevision,
        status: 'CANCELLED',
        homeScore: null,
        awayScore: null,
        cancelReason: cancelReason.trim() || null,
        reason,
      }
    }

    const parsedHomeScore = parseScore(homeScore)
    const parsedAwayScore = parseScore(awayScore)
    if (parsedHomeScore == null || parsedAwayScore == null) {
      setError('종료 경기의 홈/원정 점수는 0 이상의 정수로 입력해 주세요.')
      return null
    }
    return {
      settlementRevision: status.latestRevision,
      status: 'FINISHED',
      homeScore: parsedHomeScore,
      awayScore: parsedAwayScore,
      cancelReason: null,
      reason,
    }
  }

  const requestCorrection = () => {
    if (!status || selectedGameId == null || isBusy) return
    const request = buildCorrectionRequest()
    if (!request) return

    const resultDescription = request.status === 'CANCELLED'
      ? '경기 취소'
      : `${selectedGame?.awayTeamName ?? '원정'} ${request.awayScore} : ${request.homeScore} ${selectedGame?.homeTeamName ?? '홈'}`
    setError('')
    setConfirmation({
      action: 'correct',
      title: `경기 결과 정정 · revision #${request.settlementRevision}`,
      description: `${resultDescription}(으)로 공식 결과를 수정합니다. rollback된 최신 회차에만 적용되며, 수정 후 반드시 재정산해야 합니다. 계속하시겠습니까?`,
      variant: 'destructive',
      execute: async () => {
        const response = await correctGameResult(selectedGameId, request)
        return `결과 정정 완료 · revision #${response.settlementRevision} · ${statusLabels[response.status]} · ${formatResult(response.result)} · 점수 ${formatScore(response.awayScore, response.homeScore)}`
      },
    })
  }

  const requestResettlement = () => {
    if (!status || selectedGameId == null || status.latestRevision == null || isBusy) return
    const rollbackRevision = status.latestRevision
    setConfirmation({
      action: 'resettle',
      title: `경기 재정산 · rollback revision #${rollbackRevision}`,
      description: '정정된 경기 결과로 예측을 재정산하고 새 revision을 생성합니다. 점수와 현재 결과를 다시 확인한 뒤 실행해 주세요.',
      variant: 'destructive',
      execute: async () => {
        const response = await settleGame(selectedGameId, rollbackRevision)
        return `재정산 완료 · revision #${response.settlementRevision ?? '-'} · 대상 ${response.totalCount}건 · 적중 ${response.correctCount}건 · 실패 ${response.incorrectCount}건 · 환불 ${response.refundedCount}건 · 지급/환불 ${response.totalPaidPoints.toLocaleString()}P`
      },
    })
  }

  const terminalGame = status?.currentGameStatus === 'CANCELLED'
    || (status?.currentGameStatus === 'FINISHED'
      && status.currentGameResult != null
      && status.currentHomeScore != null
      && status.currentAwayScore != null
      && status.currentHomeScore >= 0
      && status.currentAwayScore >= 0)
  const canInitialSettle = status != null
    && status.settlementState == null
    && terminalGame
    && status.pendingPredictionCount > 0
  const canRollback = status?.settlementState === 'SETTLED'
    && status.latestRevision != null
  const canCorrect = status?.settlementState === 'ROLLED_BACK'
    && status.latestRevision != null
  const canResettle = status?.settlementState === 'ROLLED_BACK'
    && status.latestRevision != null
    && status.resultCorrectedAt != null
    && !status.correctionReviewRequired
    && terminalGame
    && status.pendingPredictionCount > 0

  return (
    <Card className="border-orange-400/40">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CircleDollarSign className="size-5 text-orange-600" /> 관리자 정산 관리
        </CardTitle>
        <CardDescription>
          {selectedDate} 경기 목록에서 대상을 선택해 현재 상태를 확인하고, 필요한 경우 rollback → 결과 정정 → 재정산 순서로 복구합니다.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-5">
        <div className="flex flex-wrap items-end gap-2">
          <label className="grid min-w-[320px] flex-1 gap-1 text-xs font-semibold">
            경기 선택
            <select
              className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
              value={selectedGameId ?? ''}
              disabled={isBusy || statusLoading}
              onChange={(event) => {
                const value = event.target.value
                setStatus(null)
                setError('')
                setNotice('')
                setSelectedGameId(value ? Number(value) : null)
              }}
            >
              <option value="">경기를 선택해 주세요</option>
              {games.map((game) => (
                <option key={game.id} value={game.id}>
                  {game.gameDate} · {statusLabels[game.status]} · {game.awayTeamName} {game.awayScore ?? '-'} : {game.homeScore ?? '-'} {game.homeTeamName} · #{game.id}
                </option>
              ))}
            </select>
          </label>
          <Button
            variant="outline"
            disabled={selectedGameId == null || isBusy || statusLoading}
            onClick={() => void reloadStatus()}
          >
            <RefreshCw className={statusLoading ? 'animate-spin' : ''} />
            {statusLoading ? '조회 중...' : '상태 새로고침'}
          </Button>
        </div>

        {games.length === 0 && (
          <div className="rounded-xl border border-dashed py-8 text-center text-sm text-muted-foreground">
            선택한 날짜의 경기가 없습니다. 위 경기 관리 영역에서 날짜를 변경해 주세요.
          </div>
        )}

        {(error || notice) && (
          <div aria-live="polite" className="grid gap-2">
            {error && (
              <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                {error}
              </div>
            )}
            {notice && (
              <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-4 py-3 text-sm text-emerald-700 dark:text-emerald-300">
                {notice}
              </div>
            )}
          </div>
        )}

        {statusLoading && !status && (
          <div className="flex items-center justify-center gap-2 rounded-xl border border-dashed py-10 text-sm text-muted-foreground">
            <LoaderCircle className="size-4 animate-spin" /> 정산 상태를 조회하고 있습니다.
          </div>
        )}

        {status && selectedGame && (
          <>
            <div className="rounded-xl border bg-muted/10 p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-lg font-black">
                    {selectedGame.awayTeamName} vs {selectedGame.homeTeamName}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {selectedGame.gameDate} {selectedGame.gameTime.slice(0, 5)} · #{selectedGame.id} · {selectedGame.stadium}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Badge variant="outline">경기 {statusLabels[status.currentGameStatus]}</Badge>
                  {status.settlementState === 'SETTLED' && <Badge>정산 완료</Badge>}
                  {status.settlementState === 'ROLLED_BACK' && <Badge variant="destructive">Rollback 완료</Badge>}
                  {status.settlementState == null && <Badge variant="outline">정산 이력 없음</Badge>}
                  {status.recoveryPending && <Badge variant="destructive">복구 진행 중</Badge>}
                  {status.correctionReviewRequired && <Badge variant="destructive">결과 재검토 필요</Badge>}
                </div>
              </div>
            </div>

            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <DetailItem label="현재 경기 상태" value={statusLabels[status.currentGameStatus]} />
              <DetailItem label="현재 결과" value={formatResult(status.currentGameResult)} />
              <DetailItem label="현재 점수 (원정 : 홈)" value={formatScore(status.currentAwayScore, status.currentHomeScore)} />
              <DetailItem label="최신 정산 회차" value={status.latestRevision == null ? '-' : `#${status.latestRevision}`} />
              <DetailItem label="정산 상태 / 출처" value={`${status.settlementState ?? '-'} / ${status.settlementSource ? sourceLabels[status.settlementSource] : '-'}`} />
              <DetailItem label="정산 스냅샷" value={status.settledGameStatus == null ? '-' : `${statusLabels[status.settledGameStatus]} · ${formatResult(status.settledGameResult)} · ${formatScore(status.settledAwayScore, status.settledHomeScore)}`} />
              <DetailItem label="정산 예측 / 현재 정산됨" value={`${status.predictionCount.toLocaleString()} / ${status.settledPredictionCount.toLocaleString()}건`} />
              <DetailItem label="현재 미정산 예측" value={`${status.pendingPredictionCount.toLocaleString()}건`} />
              <DetailItem label="정산 실행자 / 시각" value={`${status.settledByUserId == null ? '-' : `user #${status.settledByUserId}`} · ${formatDateTime(status.settledAt)}`} />
              <DetailItem label="Rollback 실행자 / 시각" value={`${status.rolledBackByUserId == null ? '-' : `user #${status.rolledBackByUserId}`} · ${formatDateTime(status.rolledBackAt)}`} />
              <DetailItem label="결과 정정 실행자 / 시각" value={`${status.resultCorrectedByUserId == null ? '-' : `user #${status.resultCorrectedByUserId}`} · ${formatDateTime(status.resultCorrectedAt)}`} />
              <DetailItem label="정정 결과 스냅샷" value={status.correctedGameStatus == null ? '-' : `${statusLabels[status.correctedGameStatus]} · ${formatResult(status.correctedGameResult)} · ${formatScore(status.correctedAwayScore, status.correctedHomeScore)}`} />
            </div>

            {(status.rollbackReason || status.resultCorrectionReason) && (
              <div className="grid gap-2 md:grid-cols-2">
                {status.rollbackReason && <DetailItem label="Rollback 사유" value={status.rollbackReason} />}
                {status.resultCorrectionReason && <DetailItem label="결과 정정 사유" value={status.resultCorrectionReason} />}
              </div>
            )}

            <div className="grid gap-2 rounded-xl border p-4">
              <p className="text-sm font-bold">오정산 복구 순서</p>
              <div className="grid gap-2 sm:grid-cols-5">
                {[
                  ['1', '현재 상태 확인', '선택 완료'],
                  ['2', '기존 정산 rollback', status.settlementState === 'ROLLED_BACK' ? '완료' : status.settlementState === 'SETTLED' ? '실행 가능' : '정산 후'],
                  ['3', '경기 결과 수정', status.resultCorrectedAt ? '완료' : status.settlementState === 'ROLLED_BACK' ? '실행 가능' : 'rollback 후'],
                  ['4', '재정산', canResettle ? '실행 가능' : status.settlementState === 'SETTLED' ? '현재 정산됨' : '결과 수정 후'],
                  ['5', '최종 상태 확인', '자동 갱신'],
                ].map(([step, label, state]) => (
                  <div key={step} className="rounded-lg bg-muted/40 p-3">
                    <p className="font-mono text-xs font-black text-orange-600">STEP {step}</p>
                    <p className="mt-1 text-xs font-semibold">{label}</p>
                    <p className="mt-1 text-[11px] text-muted-foreground">{state}</p>
                  </div>
                ))}
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <div className="grid content-start gap-3 rounded-xl border p-4">
                <div>
                  <p className="flex items-center gap-2 font-bold"><RotateCcw className="size-4" /> 정산 / Rollback / 재정산</p>
                  <p className="mt-1 text-xs text-muted-foreground">모든 실행 전 서버가 경기 및 최신 revision 상태를 다시 검증합니다.</p>
                </div>
                <Button
                  disabled={!canInitialSettle || isBusy}
                  onClick={requestInitialSettlement}
                >
                  {busyAction === 'settle' ? <LoaderCircle className="animate-spin" /> : <CircleDollarSign />}
                  {busyAction === 'settle' ? '수동 정산 중...' : '수동 정산'}
                </Button>
                {!canInitialSettle && status.settlementState == null && (
                  <p className="text-xs text-muted-foreground">
                    {!terminalGame
                      ? '종료 경기의 결과와 점수가 확정되거나 경기 취소 상태여야 정산할 수 있습니다.'
                      : status.pendingPredictionCount === 0
                        ? '정산할 미정산 예측이 없습니다.'
                        : '현재 상태에서는 수동 정산할 수 없습니다.'}
                  </p>
                )}
                <label className="grid gap-1 text-xs font-semibold">
                  Rollback 사유
                  <textarea
                    className="min-h-20 rounded-md border bg-background px-3 py-2 text-sm font-normal"
                    maxLength={255}
                    value={rollbackReason}
                    disabled={!canRollback || isBusy}
                    placeholder="오정산 원인과 rollback 목적을 입력하세요."
                    onChange={(event) => setRollbackReason(event.target.value)}
                  />
                </label>
                <Button
                  variant="destructive"
                  disabled={!canRollback || isBusy || !rollbackReason.trim()}
                  onClick={requestRollback}
                >
                  {busyAction === 'rollback' ? <LoaderCircle className="animate-spin" /> : <RotateCcw />}
                  {busyAction === 'rollback'
                    ? 'Rollback 중...'
                    : `Revision #${status.latestRevision ?? '-'} rollback`}
                </Button>
                <Button
                  variant="destructive"
                  disabled={!canResettle || isBusy}
                  onClick={requestResettlement}
                >
                  {busyAction === 'resettle' ? <LoaderCircle className="animate-spin" /> : <CheckCircle2 />}
                  {busyAction === 'resettle'
                    ? '재정산 중...'
                    : `Rollback #${status.latestRevision ?? '-'} 기준 재정산`}
                </Button>
              </div>

              <div className="grid content-start gap-3 rounded-xl border p-4">
                <div>
                  <p className="flex items-center gap-2 font-bold"><PencilLine className="size-4" /> 경기 결과 정정</p>
                  <p className="mt-1 text-xs text-muted-foreground">최신 정산을 rollback한 뒤에만 결과를 수정할 수 있습니다. 승/무/패는 입력한 점수로 서버가 계산합니다.</p>
                </div>
                <label className="grid gap-1 text-xs font-semibold">
                  정정 상태
                  <select
                    className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                    value={correctionStatus}
                    disabled={!canCorrect || isBusy}
                    onChange={(event) => setCorrectionStatus(event.target.value as CorrectionStatus)}
                  >
                    <option value="FINISHED">경기 종료</option>
                    <option value="CANCELLED">경기 취소</option>
                  </select>
                </label>
                {correctionStatus === 'FINISHED' ? (
                  <div className="grid grid-cols-2 gap-2">
                    <label className="grid gap-1 text-xs font-semibold">
                      {selectedGame.awayTeamName} 점수
                      <input
                        type="number"
                        min="0"
                        max="2147483647"
                        step="1"
                        className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                        value={awayScore}
                        disabled={!canCorrect || isBusy}
                        onChange={(event) => setAwayScore(event.target.value)}
                      />
                    </label>
                    <label className="grid gap-1 text-xs font-semibold">
                      {selectedGame.homeTeamName} 점수
                      <input
                        type="number"
                        min="0"
                        max="2147483647"
                        step="1"
                        className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                        value={homeScore}
                        disabled={!canCorrect || isBusy}
                        onChange={(event) => setHomeScore(event.target.value)}
                      />
                    </label>
                  </div>
                ) : (
                  <label className="grid gap-1 text-xs font-semibold">
                    경기 취소 사유 (선택)
                    <input
                      className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                      maxLength={255}
                      value={cancelReason}
                      disabled={!canCorrect || isBusy}
                      placeholder="우천 취소 등"
                      onChange={(event) => setCancelReason(event.target.value)}
                    />
                  </label>
                )}
                <label className="grid gap-1 text-xs font-semibold">
                  결과 정정 사유
                  <textarea
                    className="min-h-20 rounded-md border bg-background px-3 py-2 text-sm font-normal"
                    maxLength={255}
                    value={correctionReason}
                    disabled={!canCorrect || isBusy}
                    placeholder="공식 기록 정정 근거를 입력하세요."
                    onChange={(event) => setCorrectionReason(event.target.value)}
                  />
                </label>
                <Button
                  variant="destructive"
                  disabled={!canCorrect || isBusy || !correctionReason.trim()}
                  onClick={requestCorrection}
                >
                  {busyAction === 'correct' ? <LoaderCircle className="animate-spin" /> : <PencilLine />}
                  {busyAction === 'correct' ? '결과 수정 중...' : '경기 결과 수정'}
                </Button>
              </div>
            </div>

            {status.correctionReviewRequired && (
              <div className="flex gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" />
                저장된 정산 또는 정정 스냅샷과 현재 경기 결과가 다릅니다. 재정산 전에 상태를 확인하고 필요한 복구 단계를 수행해 주세요.
              </div>
            )}
          </>
        )}
      </CardContent>

      {confirmation && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="settlement-confirm-title"
        >
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle id="settlement-confirm-title">{confirmation.title}</CardTitle>
              <CardDescription>{confirmation.description}</CardDescription>
            </CardHeader>
            <CardContent className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setConfirmation(null)}>취소</Button>
              <Button
                variant={confirmation.variant}
                onClick={() => void runConfirmedAction()}
              >
                실행
              </Button>
            </CardContent>
          </Card>
        </div>
      )}
    </Card>
  )
}
