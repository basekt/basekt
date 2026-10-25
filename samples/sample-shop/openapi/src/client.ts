// A typed client of the sample shop, from the types openapi-typescript generates out of openapi.json. CI generates
// them and type-checks this file, so a change to the document that breaks a client fails the build.
import createClient from "openapi-fetch";
import type { components, paths } from "./api";

type Product = components["schemas"]["products"];
type NewOrder = components["schemas"]["orders.create"];
type ApiError = components["schemas"]["basekt.ApiError"];

const shop = createClient<paths>({ baseUrl: "http://localhost:8080", headers: { Authorization: "Bearer customer-token" } });

/** The cheapest published lamps, with the list route's typed filters, sort and limit. */
export async function cheapLamps(): Promise<Product[]> {
  const { data, error } = await shop.GET("/api/products", {
    params: { query: { categoryId: "lamps", "price[lte]": "50", published: true, sort: "price,-stock", limit: 10 } },
  });
  if (error) throw new Error(describe(error));
  return data.items;
}

/** Places an order: the body is checked against the create schema, and the answer is a typed order. */
export async function placeOrder(productId: string, quantity: number): Promise<string> {
  const order: NewOrder = { customerId: "cus-1", productId, quantity, status: "placed" };
  const { data, error, response } = await shop.POST("/api/orders", { body: order });
  if (error) throw new Error(`${describe(error)} (request ${response.headers.get("X-Request-Id")})`);
  const placed: "placed" | "paid" | "shipped" | "cancelled" = data.status;
  return `${data.id} is ${placed}`;
}

/** A category's products, through the relation route, which takes the products' filters. */
export async function inStock(categoryId: string): Promise<number> {
  const { data } = await shop.GET("/api/categories/{id}/products", {
    params: { path: { id: categoryId }, query: { "stock[gt]": 0 } },
  });
  return data?.items.length ?? 0;
}

function describe(error: ApiError): string {
  const fields = error.fields.map((field) => `${field.field}: ${field.code}`).join(", ");
  return `${error.error}: ${error.message}${fields ? ` (${fields})` : ""}`;
}

// The generated types are strict: these must not compile.
// @ts-expect-error an order has no field `discount`
export const unknownField: NewOrder = { customerId: "c", productId: "p", quantity: 1, status: "placed", discount: 5 };
// @ts-expect-error `lost` is not an order status
export const unknownStatus: NewOrder = { customerId: "c", productId: "p", quantity: 1, status: "lost" };
// @ts-expect-error prices are decimal strings in records
export const numericPrice: Product["price"] = 9.5;
