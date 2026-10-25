import type { ApiError } from "@wire"
import { ApiFailure } from "@/api/client"
import { jwtExpiry, RefreshUnavailable, type Session } from "@/auth"
import type { LoginRoute } from "@/config"

/**
 * Trades [username] and [password] for a session at the host's [login] route, which answers the token in its
 * `tokenField`, and may add a refresh token and the token's life in seconds. Fails with an [ApiFailure]: a 401 or 403
 * for refused credentials, status 0 when the server can't be reached. The password goes only to that route, on the
 * panel's own server.
 */
export async function passwordLogin(
  login: LoginRoute,
  username: string,
  password: string,
  fetchImpl: typeof fetch = (...args) => fetch(...args),
  now: () => number = Date.now,
): Promise<Session> {
  const body = await post(login.path, { [login.usernameField]: username, [login.passwordField]: password }, fetchImpl)
  return sessionFrom(login, body, null, now())
}

/**
 * Renews [session] at the login's refresh route, sending its refresh token there and nowhere else. An answer without
 * a new refresh token keeps the old one. Fails with [RefreshUnavailable] when the server can't be reached, so the
 * panel tries again, and with an [ApiFailure] when the route refuses.
 */
export async function refreshLogin(
  login: LoginRoute,
  session: Session,
  fetchImpl: typeof fetch = (...args) => fetch(...args),
  now: () => number = Date.now,
): Promise<Session> {
  if (!login.refreshPath || !session.refreshToken) throw new ApiFailure(401, null, null)
  let body: Record<string, unknown> | null
  try {
    body = await post(login.refreshPath, { [login.refreshTokenField]: session.refreshToken }, fetchImpl)
  } catch (error) {
    if (error instanceof ApiFailure && error.network) throw new RefreshUnavailable(error.message)
    throw error
  }
  return sessionFrom(login, body, session.refreshToken, now())
}

async function post(path: string, payload: Record<string, string>, fetchImpl: typeof fetch): Promise<Record<string, unknown> | null> {
  const call = { method: "POST", path }
  let response: Response
  try {
    response = await fetchImpl(path, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(payload),
    })
  } catch {
    throw new ApiFailure(0, null, call)
  }
  const body = (await response.json().catch(() => null)) as Record<string, unknown> | null
  if (!response.ok) throw new ApiFailure(response.status, asError(body), { ...call, requestId: response.headers.get("X-Request-Id") })
  return body
}

/** The session in a login or refresh answer. A refresh token is kept only when there is a route to send it to. */
function sessionFrom(login: LoginRoute, body: Record<string, unknown> | null, previousRefreshToken: string | null, now: number): Session {
  const token = body?.[login.tokenField]
  if (typeof token !== "string" || !token) throw new ApiFailure(200, null, { method: "POST", path: login.path })
  const refreshToken = body?.[login.refreshTokenField]
  const expiresIn = body?.[login.expiresInField]
  return {
    token,
    source: "login",
    refreshToken: login.refreshPath ? (typeof refreshToken === "string" && refreshToken ? refreshToken : previousRefreshToken) : null,
    expiresAt: typeof expiresIn === "number" && expiresIn > 0 ? now + expiresIn * 1000 : jwtExpiry(token, now),
  }
}

function asError(body: Record<string, unknown> | null): ApiError | null {
  return body && typeof body.error === "string" && typeof body.message === "string"
    ? { error: body.error as ApiError["error"], message: body.message, fields: [], requestId: (body.requestId as string | undefined) ?? null }
    : null
}
