import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { apiRequest, readNothing, toResult } from "./browser";

type Call = { url: string; method: string; headers: Record<string, string>; body: string | undefined; init: RequestInit };

const originalFetch = globalThis.fetch;
const globals = globalThis as unknown as { document?: { cookie: string } };
let calls: Call[];
let responses: (() => Response | Promise<Response>)[];

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

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
      init: init ?? {},
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

const readAny = (data: unknown) => (data === undefined ? null : data);

describe("apiRequest", () => {
  it("sends PUT with CSRF header, JSON body, same-origin credentials and no-store", async () => {
    responses.push(() => raw(200, '{"success":true,"data":{"workId":9007199254740993,"score":4.5}}'));
    const result = await apiRequest("PUT", "/me/ratings/9007199254740993", readAny, { score: 4.5 });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/v1/me/ratings/9007199254740993");
    assert.equal(calls[0].method, "PUT");
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], "token-1");
    assert.equal(calls[0].headers["Content-Type"], "application/json");
    assert.equal(calls[0].body, '{"score":4.5}');
    assert.equal(calls[0].init.credentials, "same-origin");
    assert.equal(calls[0].init.cache, "no-store");
    assert.equal(result.kind, "ok");
    // 무손실 파서: workId 는 원문 문자열
    assert.deepEqual(result.kind === "ok" ? result.data : null, { workId: "9007199254740993", score: 4.5 });
  });

  it("sends DELETE with CSRF header and does not parse the 204 body", async () => {
    let textRead = false;
    responses.push(() => {
      const response = new Response(null, { status: 204 });
      Object.defineProperty(response, "text", {
        value: () => {
          textRead = true;
          return Promise.resolve("not json");
        },
      });
      return response;
    });
    const result = await apiRequest("DELETE", "/me/ratings/1", readNothing);

    assert.equal(calls[0].method, "DELETE");
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], "token-1");
    assert.equal(calls[0].body, undefined);
    assert.equal(calls[0].headers["Content-Type"], undefined);
    assert.equal(textRead, false);
    assert.deepEqual(result, { kind: "ok", status: 204, data: true });
  });

  it("sends GET without CSRF header", async () => {
    responses.push(() => json(200, { success: true, data: { ok: 1 } }));
    await apiRequest("GET", "/me/ratings/1", readAny);
    assert.equal(calls[0].method, "GET");
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], undefined);
  });

  it("fetches a CSRF token first when the cookie is missing", async () => {
    globals.document = { cookie: "" };
    responses.push(() => {
      globals.document = { cookie: "XSRF-TOKEN=fresh" };
      return new Response(null, { status: 204 });
    });
    responses.push(() => new Response(null, { status: 204 }));
    const result = await apiRequest("DELETE", "/me/ratings/1", readNothing);

    assert.deepEqual(
      calls.map((call) => `${call.method} ${call.url}`),
      ["GET /api/v1/auth/csrf", "DELETE /api/v1/me/ratings/1"],
    );
    assert.equal(calls[1].headers["X-XSRF-TOKEN"], "fresh");
    assert.equal(result.kind, "ok");
  });

  it("refreshes the CSRF token and retries exactly once on INVALID_CSRF_TOKEN", async () => {
    const csrfError = { success: false, error: { code: "INVALID_CSRF_TOKEN", message: "CSRF" } };
    responses.push(() => json(403, csrfError));
    responses.push(() => {
      globals.document = { cookie: "XSRF-TOKEN=token-2" };
      return new Response(null, { status: 204 });
    });
    responses.push(() => json(403, csrfError));
    const result = await apiRequest("PUT", "/me/ratings/1", readAny, { score: 1 });

    assert.deepEqual(
      calls.map((call) => `${call.method} ${call.url}`),
      ["PUT /api/v1/me/ratings/1", "GET /api/v1/auth/csrf", "PUT /api/v1/me/ratings/1"],
    );
    assert.equal(calls[2].headers["X-XSRF-TOKEN"], "token-2");
    assert.equal(result.kind === "error" ? result.code : null, "INVALID_CSRF_TOKEN");
  });

  it("does not resend a mutation after a network failure", async () => {
    responses.push(() => {
      throw new TypeError("network down");
    });
    const result = await apiRequest("PUT", "/me/ratings/1", readAny, { score: 1 });
    assert.equal(calls.length, 1);
    assert.deepEqual(result, { kind: "unavailable" });
  });

  it("does not resend a mutation on other errors", async () => {
    responses.push(() => json(401, { success: false, error: { code: "UNAUTHORIZED", message: "로그인이 필요합니다." } }));
    const result = await apiRequest("PUT", "/me/ratings/1", readAny, { score: 1 });
    assert.equal(calls.length, 1);
    assert.equal(result.kind === "error" ? result.status : null, 401);
  });
});

describe("toResult", () => {
  it("treats 502·503·504 and non-JSON bodies as unavailable", async () => {
    for (const status of [502, 503, 504]) {
      assert.deepEqual(await toResult(json(status, { success: false }), readAny), { kind: "unavailable" });
    }
    // 프록시 연결 실패(503 SERVICE_UNAVAILABLE), 본문 없는 503, JSON 이 아닌 503, 다른 코드의 503
    assert.deepEqual(
      await toResult(json(503, { success: false, error: { code: "SERVICE_UNAVAILABLE", message: "x" } }, { "retry-after": "1" }), readAny),
      { kind: "unavailable" },
    );
    assert.deepEqual(await toResult(new Response(null, { status: 503 }), readAny), { kind: "unavailable" });
    assert.deepEqual(await toResult(raw(503, "<html>busy</html>"), readAny), { kind: "unavailable" });
    assert.deepEqual(await toResult(json(503, { success: false, error: "AUTH_BUSY" }), readAny), { kind: "unavailable" });
    // 502·504 는 본문이 AUTH_BUSY 여도 연결 실패
    for (const status of [502, 504]) {
      assert.deepEqual(
        await toResult(json(status, { success: false, error: { code: "AUTH_BUSY" } }), readAny),
        { kind: "unavailable" },
      );
    }
    assert.deepEqual(await toResult(raw(500, "<html>"), readAny), { kind: "unavailable" });
    assert.deepEqual(await toResult(raw(200, "<html>"), readAny), { kind: "unavailable" });
  });

  it("returns 503 AUTH_BUSY as an error with Retry-After, distinct from an outage", async () => {
    const result = await toResult(
      json(503, { success: false, error: { code: "AUTH_BUSY", message: "요청이 많아 지금은 처리할 수 없습니다." } }, { "retry-after": "1" }),
      readAny,
    );
    assert.deepEqual(result, {
      kind: "error",
      status: 503,
      code: "AUTH_BUSY",
      message: "요청이 많아 지금은 처리할 수 없습니다.",
      fieldErrors: [],
      retryAfterSeconds: 1,
    });
    const noHeader = await toResult(json(503, { success: false, error: { code: "AUTH_BUSY" } }), readAny);
    assert.equal(noHeader.kind === "error" ? noHeader.retryAfterSeconds : "x", null);
  });

  it("treats a success body of an unexpected shape as unavailable", async () => {
    const result = await toResult(json(200, { success: true, data: { weird: true } }), () => null);
    assert.deepEqual(result, { kind: "unavailable" });
  });

  it("returns field errors and Retry-After", async () => {
    const result = await toResult(
      json(
        400,
        { success: false, error: { code: "INVALID_INPUT", message: "x", fieldErrors: [{ field: "score", reason: "r" }, { bad: 1 }] } },
        { "retry-after": "60" },
      ),
      readAny,
    );
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.deepEqual(result.fieldErrors, [{ field: "score", reason: "r" }]);
      assert.equal(result.retryAfterSeconds, 60);
    }
  });
});
