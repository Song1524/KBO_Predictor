import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

// Execute the actual TypeScript helpers in memory, without a new test framework or build files.
const compile = (source) => `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText).toString('base64')}`
const clientUrl = compile(await readFile(new URL('../src/lib/api-client.ts', import.meta.url), 'utf8'))
const { apiFetch, subscribeAuthenticationFailure, markAuthenticationChanged } = await import(clientUrl)
const sessionSource = (await readFile(new URL('../src/lib/auth-session.ts', import.meta.url), 'utf8'))
  .replace("'./api-client'", JSON.stringify(clientUrl))
const { logoutSession } = await import(compile(sessionSource))

let calls
beforeEach(() => {
  calls = []
  let cookie = 'XSRF-TOKEN=old'
  globalThis.document = { get cookie() { return cookie }, set cookie(value) {
    cookie = value.includes('Max-Age=0') ? '' : value
  } }
  globalThis.window = { location: { origin: 'http://localhost' } }
  markAuthenticationChanged()
})

function mockFetch(respond) {
  globalThis.fetch = async (input, init) => {
    calls.push({ path: input instanceof Request ? input.url : String(input), init })
    return respond(input, init, calls.length)
  }
}
const response = (status, code) => new Response(null, {
  status, headers: code ? { 'X-Auth-Error': code } : {},
})

test('logout success confirms completion without /me', async () => {
  mockFetch(() => response(204))
  assert.deepEqual(await logoutSession(), { completed: true, errorMessage: null })
  assert.equal(calls.length, 1)
})

test('logout HTTP failure and live session retains authenticated state and can retry', async () => {
  mockFetch((input) => response(String(input).endsWith('/me') ? 200 : 500))
  assert.equal((await logoutSession()).completed, false)
  assert.ok((await logoutSession()).errorMessage)
  mockFetch(() => response(204))
  assert.equal((await logoutSession()).completed, true)
})

test('network failure and unknown /me outcome does not complete logout', async () => {
  mockFetch(() => { throw new TypeError('offline') })
  assert.equal((await logoutSession()).completed, false)
  assert.equal(calls.length, 2)
})

test('lost logout response followed by confirmed absent session completes logout', async () => {
  mockFetch((input) => {
    if (String(input).endsWith('/logout')) throw new TypeError('lost response')
    return response(401, 'SESSION_EXPIRED')
  })
  assert.equal((await logoutSession()).completed, true)
})

test('expired session logout is confirmed by /me 401', async () => {
  mockFetch(() => response(401, 'SESSION_EXPIRED'))
  assert.equal((await logoutSession()).completed, true)
  assert.deepEqual(calls.map((call) => call.path), ['/api/auth/logout', '/api/auth/me'])
})

test('403 or 503 during logout and session probe does not imply logout', async () => {
  for (const status of [403, 503]) {
    mockFetch(() => response(status, status === 403 ? 'FORBIDDEN' : undefined))
    assert.equal((await logoutSession()).completed, false)
  }
})

test('logout 401 with an unavailable session probe preserves state until confirmation', async () => {
  let notified = false
  const unsubscribe = subscribeAuthenticationFailure(() => { notified = true })
  try {
    mockFetch((input) => response(String(input).endsWith('/logout') ? 401 : 503))
    assert.equal((await logoutSession()).completed, false)
    assert.equal(notified, false)
  } finally { unsubscribe() }
})

test('missing CSRF cookie is bootstrapped before mutation', async () => {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  mockFetch((input, init) => {
    if (String(input).endsWith('/csrf')) { document.cookie = 'XSRF-TOKEN=new'; return response(204) }
    assert.equal(init.headers.get('X-XSRF-TOKEN'), 'new')
    return response(200)
  })
  await apiFetch('/api/change', { method: 'POST', body: 'payload' })
  assert.equal(calls.length, 2)
})

test('CSRF-specific rejection refreshes token and retries mutation once', async () => {
  mockFetch((input, init, index) => {
    if (String(input).endsWith('/csrf')) { document.cookie = 'XSRF-TOKEN=new'; return response(204) }
    assert.equal(init.body, 'payload')
    if (index === 1) return response(403, 'CSRF_INVALID')
    assert.equal(init.headers.get('X-XSRF-TOKEN'), 'new')
    return response(200)
  })
  assert.equal((await apiFetch('/api/change', { method: 'PATCH', body: 'payload' })).status, 200)
  assert.equal(calls.length, 3)
})

test('repeated invalid CSRF stops after the single retry', async () => {
  mockFetch((input) => {
    if (String(input).endsWith('/csrf')) { document.cookie = 'XSRF-TOKEN=new'; return response(204) }
    return response(403, 'CSRF_INVALID')
  })
  assert.equal((await apiFetch('/api/change', { method: 'DELETE' })).status, 403)
  assert.equal(calls.length, 3)
})

test('permission 403 and ordinary server failures never retry or revoke auth', async () => {
  const failures = []
  const unsubscribe = subscribeAuthenticationFailure((failure) => failures.push(failure))
  try {
    for (const status of [403, 500]) {
      mockFetch(() => response(status, status === 403 ? 'FORBIDDEN' : undefined))
      await apiFetch('/api/change', { method: 'POST' })
    }
    assert.equal(calls.length, 2)
    assert.deepEqual(failures, [])
  } finally { unsubscribe() }
})

test('GET does not retry even when response says CSRF_INVALID', async () => {
  mockFetch(() => response(403, 'CSRF_INVALID'))
  await apiFetch('/api/read')
  assert.equal(calls.length, 1)
})

test('session expiry, absence and revocation publish distinct authentication failures', async () => {
  const failures = []
  const unsubscribe = subscribeAuthenticationFailure((failure) => failures.push(failure))
  try {
    for (const code of ['SESSION_EXPIRED', 'NOT_AUTHENTICATED', 'SESSION_REVOKED']) {
      mockFetch(() => response(401, code))
      await apiFetch('/api/auth/me')
    }
    assert.deepEqual(failures, ['SESSION_EXPIRED', 'NOT_AUTHENTICATED', 'SESSION_REVOKED'])
  } finally { unsubscribe() }
})

test('bad login credentials do not clear a current authenticated state', async () => {
  let notified = false
  const unsubscribe = subscribeAuthenticationFailure(() => { notified = true })
  try {
    mockFetch(() => response(401))
    await apiFetch('/api/auth/login', { method: 'POST' })
    assert.equal(notified, false)
  } finally { unsubscribe() }
})

test('revoked session during login is distinguished from bad credentials', async () => {
  let failure
  const unsubscribe = subscribeAuthenticationFailure((code) => { failure = code })
  try {
    mockFetch(() => response(401, 'SESSION_REVOKED'))
    await apiFetch('/api/auth/login', { method: 'POST' })
    assert.equal(failure, 'SESSION_REVOKED')
  } finally { unsubscribe() }
})

test('late 401 from an old request cannot revoke a new login', async () => {
  let finish
  mockFetch(() => new Promise((resolve) => { finish = resolve }))
  let notified = false
  const unsubscribe = subscribeAuthenticationFailure(() => { notified = true })
  try {
    const pending = apiFetch('/api/auth/me')
    markAuthenticationChanged()
    finish(response(401, 'SESSION_EXPIRED'))
    await pending
    assert.equal(notified, false)
  } finally { unsubscribe() }
})

test('Request inputs keep caller headers and replay the cloned body after CSRF rejection', async () => {
  const bodies = []
  mockFetch(async (input, init, index) => {
    if (String(input).endsWith('/csrf')) { document.cookie = 'XSRF-TOKEN=new'; return response(204) }
    assert.equal(init.headers.get('X-Custom'), 'kept')
    bodies.push(await input.text())
    return response(index === 1 ? 403 : 200, index === 1 ? 'CSRF_INVALID' : undefined)
  })
  const request = new Request('http://localhost/api/change', {
    method: 'POST', body: 'payload', headers: { 'X-Custom': 'kept' },
  })
  assert.equal((await apiFetch(request)).status, 200)
  assert.deepEqual(bodies, ['payload', 'payload'])
})
