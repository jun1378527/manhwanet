import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { deleteMyRating, fetchMyRating, saveMyRating } from "./client-api";

type Call = { url: string; method: string; headers: Record<string, string>; body: string | undefined };

const originalFetch = globalThis.fetch;
const globals = globalThis as unknown as { document?: { cookie: string } };
let calls: Call[];
let responses: (() => Response)[];

const VIEWER = "9007199254740993";

function raw(status: number, text: string): Response {
  return new Response(text, { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  calls = [];
  responses = [];
  globals.document = { cookie: "XSRF-TOKEN=token-1" };
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body as string | undefined,
    });
    const next = responses.shift();
    if (!next) {
      throw new Error("unexpected fetch");
    }
    return next();
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  delete globals.document;
});

describe("rating client api expected user", () => {
  it("sends the screen user id on get, put, and delete", async () => {
    responses.push(() => raw(200, `{"success":true,"data":{"workId":1,"score":null,"createdAt":null,"updatedAt":null}}`));
    responses.push(() => raw(200, `{"success":true,"data":{"workId":1,"score":4.5,"createdAt":"2026-10-08T00:00:00Z","updatedAt":"2026-10-08T00:00:00Z"}}`));
    responses.push(() => raw(204, ""));
    await fetchMyRating("1", VIEWER);
    await saveMyRating("1", VIEWER, 4.5);
    await deleteMyRating("1", VIEWER);
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[1].method, "PUT");
    assert.equal(calls[1].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[1].body, '{"score":4.5}');
    assert.equal(calls[2].method, "DELETE");
    assert.equal(calls[2].headers["X-Expected-User-Id"], VIEWER);
  });

  it("does not refresh CSRF or retry on USER_MISMATCH", async () => {
    responses.push(() => raw(409, '{"success":false,"error":{"code":"USER_MISMATCH","message":"사용자 불일치"}}'));
    const result = await saveMyRating("1", VIEWER, 4);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(result.kind === "error" ? result.code : null, "USER_MISMATCH");
  });

  it("keeps the same expected user on the CSRF retry", async () => {
    responses.push(() => raw(403, '{"success":false,"error":{"code":"INVALID_CSRF_TOKEN","message":"csrf"}}'));
    responses.push(() => {
      globals.document = { cookie: "XSRF-TOKEN=token-of-new-account" };
      return new Response(null, { status: 204 });
    });
    responses.push(() => raw(409, '{"success":false,"error":{"code":"USER_MISMATCH","message":"사용자 불일치"}}'));
    const result = await deleteMyRating("1", VIEWER);
    assert.deepEqual(
      calls.map((call) => `${call.method} ${call.url}`),
      ["DELETE /api/v1/me/ratings/1", "GET /api/v1/auth/csrf", "DELETE /api/v1/me/ratings/1"],
    );
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[2].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[2].headers["X-XSRF-TOKEN"], "token-of-new-account");
    assert.equal(result.kind === "error" ? result.code : null, "USER_MISMATCH");
  });

  it("refuses to send without a valid expected user id", async () => {
    assert.throws(() => saveMyRating("1", "9007199254740992.0", 4), /invalid expected user id/);
    assert.equal(calls.length, 0);
  });
});
