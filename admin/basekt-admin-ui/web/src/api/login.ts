import type { ApiError } from "@wire"
import { ApiFailure } from "@/api/client"
import type { LoginRoute } from "@/config"

/**
 * Trades [username] and [password] for a bearer token at the host's [login] route, which answers the token in its
 * `tokenField`. Fails with an [ApiFailure]: a 401 or 403 for refused credentials, status 0 when the server can't be
 * reached. The password goes only to that route, on the panel's own server.
 */
export async function passwordLogin(
  login: LoginRoute,
  username: string,
  password: string,
  fetchImpl: typeof fetch = (...args) => fetch(...args),
): Promise<string> {
  const call = { method: "POST", path: login.path }
  let response: Response
  try {
    response = await fetchImpl(login.path, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ [login.usernameField]: username, [login.passwordField]: password }),
    })
  } catch {
    throw new ApiFailure(0, null, call)
  }
  const body = (await response.json().catch(() => null)) as Record<string, unknown> | null
  if (!response.ok) throw new ApiFailure(response.status, asError(body), { ...call, requestId: response.headers.get("X-Request-Id") })
  const token = body?.[login.tokenField]
  if (typeof token !== "string" || !token) {
    throw new ApiFailure(response.status, null, call)
  }
  return token
}

function asError(body: Record<string, unknown> | null): ApiError | null {
  return body && typeof body.error === "string" && typeof body.message === "string"
    ? { error: body.error as ApiError["error"], message: body.message, fields: [], requestId: (body.requestId as string | undefined) ?? null }
    : null
}
