import { describe, expect, it } from "vitest"
import { AdminApi, ApiFailure } from "@/api/client"
import { apiError, fakeFetch, librarySchema, page } from "@/test/fixtures"

describe("AdminApi", () => {
  it("calls the API path with the token and the browser's cookies", async () => {
    const fetch = fakeFetch(({ url }) => (url.pathname === "/back-office/api/schema" ? { body: librarySchema } : undefined))
    const api = new AdminApi("/back-office/api", () => "t0k3n", fetch)
    expect(await api.schema()).toEqual(librarySchema)
    expect(fetch.calls[0].headers.Authorization).toBe("Bearer t0k3n")
  })

  it("sends no Authorization header without a token, and writes list parameters", async () => {
    const fetch = fakeFetch(() => ({ body: page([]) }))
    await new AdminApi("/admin/api", () => null, fetch).list("books", { "fee[gte]": "1", sort: "-fee", search: "" })
    const call = fetch.calls[0]
    expect(call.headers.Authorization).toBeUndefined()
    expect(call.url.pathname).toBe("/admin/api/resources/books/records")
    expect([...call.url.searchParams]).toEqual([["fee[gte]", "1"], ["sort", "-fee"]])
  })

  it("turns an error body into a typed failure", async () => {
    const error = apiError("validation_failed", "Invalid.", [{ field: "fee", code: "min", message: "At least 0.01." }])
    const fetch = fakeFetch(() => ({ status: 422, body: error }))
    const failure = await new AdminApi("/admin/api", () => null, fetch).create("books", { fee: "0" }).catch((e: unknown) => e)
    expect(failure).toBeInstanceOf(ApiFailure)
    expect((failure as ApiFailure).error).toEqual(error)
    expect(fetch.calls[0].body).toEqual({ fee: "0" })
  })
})
