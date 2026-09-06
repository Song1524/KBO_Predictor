import type { UserApiResponse } from '@/lib/api-types'

function communityDate(value: string) {
  const hasZone = /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
  return new Date(hasZone ? value : `${value}+09:00`)
}

export function formatCommunityListDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
  }).format(communityDate(value))
}

export function formatCommunityDateTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(communityDate(value))
}

export async function communityApiError(
  response: Response,
  fallback: string,
) {
  const body = await response.json().catch(() => null)
  if (response.status === 429) {
    const retryAfterSeconds = Number(body?.retryAfterSeconds)
    if (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
      return `${body?.message ?? '너무 빠르게 작성하고 있습니다.'} ${Math.ceil(retryAfterSeconds)}초 후 다시 시도해주세요.`
    }
  }
  return body?.detail ?? body?.message ?? fallback
}

export function openLoginDialog() {
  window.dispatchEvent(new Event('playball:open-login'))
}

export function isAdmin(user: UserApiResponse | null) {
  return user?.role === 'ADMIN' || user?.role === 'ROLE_ADMIN'
}
