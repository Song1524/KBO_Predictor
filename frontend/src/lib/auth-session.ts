import { apiFetch } from './api-client'

export type LogoutResult = { completed: boolean; errorMessage: string | null }

/** A failed logout is confirmed with /me before declaring the session gone. */
export async function logoutSession(): Promise<LogoutResult> {
  try {
    const response = await apiFetch('/api/auth/logout', { method: 'POST' })
    if (response.ok) return { completed: true, errorMessage: null }
  } catch { /* Network failure leaves the server outcome unknown. */ }
  try {
    const session = await apiFetch('/api/auth/me')
    if (session.status === 401) return { completed: true, errorMessage: null }
  } catch { /* Keep the local user until logout or session absence is confirmed. */ }
  return { completed: false, errorMessage: '로그아웃을 확인하지 못했습니다. 다시 시도해 주세요.' }
}
