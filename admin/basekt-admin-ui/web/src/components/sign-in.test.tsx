import { screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { apiError, config, fakeFetch, helpDeskSchema, librarySchema, page } from "@/test/fixtures"
import { renderPanel } from "@/test/render"

const withLogin = {
  ...config,
  login: { path: "/auth/login", usernameField: "email", usernameLabel: "Email", passwordField: "password", tokenField: "token" },
}

describe("signing in with a password", () => {
  it("posts the name and password to the host's login route, and uses the token it answers", async () => {
    const user = userEvent.setup()
    const fetch = fakeFetch(({ method, url, headers, body }) => {
      if (url.pathname === "/auth/login" && method === "POST") {
        const { email, password } = body as { email: string; password: string }
        return email === "bea@shop.test" && password === "right" ? { body: { token: "t-bea" } } : { status: 401, body: apiError("unauthorized", "The email or password is wrong.") }
      }
      if (url.pathname === "/admin/api/schema") return headers.Authorization === "Bearer t-bea" ? { body: librarySchema } : { status: 401, body: apiError("unauthorized", "Sign in.") }
      if (url.pathname === "/admin/api/resources/books/records") return { body: page([]) }
    })
    renderPanel("/admin/r/books", fetch, null, withLogin)

    await user.type(await screen.findByLabelText("Email"), "bea@shop.test")
    await user.type(screen.getByLabelText("Password"), "wrong")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("The email or password is wrong.")).toBeInTheDocument()

    await user.clear(screen.getByLabelText("Password"))
    await user.type(screen.getByLabelText("Password"), "right")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
    expect(fetch.calls.at(-1)!.headers.Authorization).toBe("Bearer t-bea")
    // The password went only to the login route, and never into a URL.
    const sent = fetch.calls.filter((call) => JSON.stringify(call.body ?? "").includes("right") || call.url.search.includes("right"))
    expect(sent.length).toBe(1)
    expect(sent[0].url.pathname).toBe("/auth/login")
  })

  it("keeps the token way in, and says when the server can't be reached", async () => {
    const user = userEvent.setup()
    const answers = fakeFetch(({ url, headers }) => {
      if (url.pathname === "/admin/api/schema") return headers.Authorization === "Bearer pasted" ? { body: helpDeskSchema } : { status: 401, body: apiError("unauthorized", "Sign in.") }
      if (url.pathname === "/admin/api/resources/tickets/records") return { body: page([]) }
    })
    const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === "/auth/login") throw new TypeError("Failed to fetch")
      return answers(input, init)
    }) as typeof globalThis.fetch
    renderPanel("/admin/r/tickets", fetch, null, withLogin)

    await user.type(await screen.findByLabelText("Email"), "cal@shop.test")
    await user.type(screen.getByLabelText("Password"), "anything")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText(/Couldn't reach the server/)).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Use an access token" }))
    await user.type(screen.getByLabelText("Access token"), "pasted")
    await user.click(screen.getByRole("button", { name: "Sign in" }))
    expect(await screen.findByText("No records yet.")).toBeInTheDocument()
  })

  it("offers only the token without a login route", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/admin/api/schema" ? { status: 401, body: apiError("unauthorized", "Sign in.") } : undefined))
    renderPanel("/admin/", fetch)
    expect(await screen.findByLabelText("Access token")).toBeInTheDocument()
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /password/ })).not.toBeInTheDocument()
  })
})
