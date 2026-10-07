const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfTokenRequest: Promise<string> | null = null

function readCookie(name: string): string | null {
  const encodedName = `${encodeURIComponent(name)}=`
  const cookie = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(encodedName))

  return cookie == null
    ? null
    : decodeURIComponent(cookie.slice(encodedName.length))
}

async function getCsrfToken(force = false): Promise<string> {
  const existingToken = readCookie(CSRF_COOKIE_NAME)
  if (!force && existingToken != null) return existingToken

  if (csrfTokenRequest == null) {
    csrfTokenRequest = (async () => {
      if (force) document.cookie = `${CSRF_COOKIE_NAME}=; Max-Age=0; Path=/`
      const response = await apiFetch('/api/auth/csrf', {
        credentials: 'include',
      })
      if (!response.ok) {
        throw new Error('CSRF 토큰을 발급받지 못했습니다.')
      }

      const issuedToken = readCookie(CSRF_COOKIE_NAME)
      if (issuedToken == null) {
        throw new Error('CSRF 토큰 쿠키가 없습니다.')
      }
      return issuedToken
    })().finally(() => {
      csrfTokenRequest = null
    })
  }

  return csrfTokenRequest
}

export type AuthenticationFailure = 'NOT_AUTHENTICATED' | 'SESSION_EXPIRED' | 'SESSION_REVOKED'
const authenticationListeners = new Set<(failure: AuthenticationFailure) => void>()
let authenticationVersion = 0

export function markAuthenticationChanged() { authenticationVersion += 1 }

export function subscribeAuthenticationFailure(listener: (failure: AuthenticationFailure) => void) {
  authenticationListeners.add(listener)
  return () => { authenticationListeners.delete(listener) }
}

export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const requestVersion = authenticationVersion
  const method = (
    init.method ?? (input instanceof Request ? input.method : 'GET')
  ).toUpperCase()
  const headers = new Headers(input instanceof Request ? input.headers : undefined)
  new Headers(init.headers).forEach((value, key) => headers.set(key, value))

  if (UNSAFE_METHODS.has(method)) {
    headers.set(CSRF_HEADER_NAME, await getCsrfToken())
  }

  const requestInput = input instanceof Request ? input.clone() : input
  const requestInit = {
    ...init,
    credentials: init.credentials ?? 'include',
    headers,
  } satisfies RequestInit
  let response = await fetch(input instanceof Request ? input.clone() : input, requestInit)
  if (UNSAFE_METHODS.has(method) && response.status === 403
      && response.headers.get('X-Auth-Error') === 'CSRF_INVALID'
      && !(init.body instanceof ReadableStream)) {
    headers.set(CSRF_HEADER_NAME, await getCsrfToken(true))
    response = await fetch(requestInput, requestInit)
  }
  const path = new URL(input instanceof Request ? input.url : String(input), window.location.origin).pathname
  const code = response.headers.get('X-Auth-Error')
  if (response.status === 401 && requestVersion === authenticationVersion
      && path !== '/api/auth/logout'
      && ((path !== '/api/auth/login' && path !== '/api/auth/signup') || code === 'SESSION_REVOKED')) {
    const failure: AuthenticationFailure = code === 'SESSION_REVOKED' ? code
      : code === 'SESSION_EXPIRED' ? code : 'NOT_AUTHENTICATED'
    markAuthenticationChanged()
    authenticationListeners.forEach((listener) => listener(failure))
  }
  return response
}
