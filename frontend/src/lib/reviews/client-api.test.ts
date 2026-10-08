import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { createMyReview, deleteMyReview, fetchMyReview, fetchReviewPage, updateMyReview } from "./client-api";

type Call = { url: string; method: string; headers: Record<string, string>; body: string | undefined };

const originalFetch = globalThis.fetch;
const globals = globalThis as unknown as { document?: { cookie: string } };
let calls: Call[];
let responses: (() => Response)[];

const PAST_SAFE = "9007199254740993";
const VIEWER = "9007199254740995"; // 큰 사용자 ID (2^53+3)

function raw(status: number, text: string): Response {
  return new Response(text, { status, headers: { "content-type": "application/json" } });
}

const reviewJson = (id: string, version: string) =>
  `{"success":true,"data":{"workId":${PAST_SAFE},"review":{"id":${id},"body":"<b>본문</b>\\n둘째 줄","containsSpoiler":true,"version":${version},"createdAt":"2026-10-08T00:00:00Z","updatedAt":"2026-10-08T00:00:00Z","edited":false}}}`;

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

describe("review client api", () => {
  it("reads my review with exact ids and version", async () => {
    responses.push(() => raw(200, reviewJson(PAST_SAFE, PAST_SAFE)));
    const result = await fetchMyReview(PAST_SAFE, VIEWER);
    assert.equal(calls[0].url, `/api/v1/me/reviews/${PAST_SAFE}`);
    assert.equal(calls[0].method, "GET");
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], undefined);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.data.workId, PAST_SAFE);
      assert.equal(result.data.review?.id, PAST_SAFE);
      assert.equal(result.data.review?.version, PAST_SAFE);
      assert.equal(result.data.review?.body, "<b>본문</b>\n둘째 줄");
    }
  });

  it("creates with POST, CSRF header and a JSON body", async () => {
    responses.push(() => raw(201, reviewJson("7", "0")));
    const result = await createMyReview("21", VIEWER, "본문\n<b>", false);
    assert.equal(calls[0].method, "POST");
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[0].url, "/api/v1/me/reviews/21");
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], "token-1");
    assert.equal(calls[0].headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(calls[0].body ?? ""), { body: "본문\n<b>", containsSpoiler: false });
    assert.equal(result.kind, "ok");
  });

  it("updates with PUT and sends large reviewId and version as exact JSON numbers", async () => {
    responses.push(() => raw(200, reviewJson(PAST_SAFE, "9007199254740994")));
    const result = await updateMyReview("21", VIEWER, { reviewId: PAST_SAFE, version: PAST_SAFE }, "고친 본문", true);
    assert.equal(calls[0].method, "PUT");
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(
      calls[0].body,
      `{"reviewId":${PAST_SAFE},"version":${PAST_SAFE},"body":"고친 본문","containsSpoiler":true}`,
    );
    assert.equal(result.kind === "ok" ? result.data.review?.version : null, "9007199254740994");
  });

  it("deletes with query parameters, no body, and does not parse 204", async () => {
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
    const result = await deleteMyReview("21", VIEWER, { reviewId: PAST_SAFE, version: "3" });
    assert.equal(calls[0].method, "DELETE");
    assert.equal(calls[0].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[0].url, `/api/v1/me/reviews/21?reviewId=${PAST_SAFE}&version=3`);
    assert.equal(calls[0].body, undefined);
    assert.equal(calls[0].headers["Content-Type"], undefined);
    assert.equal(calls[0].headers["X-XSRF-TOKEN"], "token-1");
    assert.equal(textRead, false);
    assert.deepEqual(result, { kind: "ok", status: 204, data: true });
  });

  it("returns 409 conflicts as errors without resending", async () => {
    responses.push(() =>
      raw(409, '{"success":false,"error":{"code":"REVIEW_VERSION_CONFLICT","message":"충돌"}}'),
    );
    const result = await updateMyReview("21", VIEWER, { reviewId: "7", version: "0" }, "본문", false);
    assert.equal(calls.length, 1);
    assert.equal(result.kind === "error" ? result.code : null, "REVIEW_VERSION_CONFLICT");
  });

  it("does not resend a mutation after a network failure", async () => {
    responses.push(() => {
      throw new TypeError("network down");
    });
    const result = await deleteMyReview("21", VIEWER, { reviewId: "7", version: "0" });
    assert.equal(calls.length, 1);
    assert.deepEqual(result, { kind: "unavailable" });
  });

  it("reads a public page with page and size", async () => {
    responses.push(() =>
      raw(200, '{"success":true,"data":{"content":[],"page":2,"size":10,"totalElements":0,"totalPages":0,"hasNext":false}}'),
    );
    const result = await fetchReviewPage(PAST_SAFE, 2);
    assert.equal(calls[0].url, `/api/v1/works/${PAST_SAFE}/reviews?page=2&size=10`);
    assert.equal(result.kind, "ok");
  });

  it("refuses to build a request with an invalid target before sending", async () => {
    await assert.rejects(() => updateMyReview("21", VIEWER, { reviewId: "1.0", version: "0" }, "본문", false));
    assert.equal(calls.length, 0);
  });

  it("sends the same expected user on the CSRF retry, so a switched account is rejected by the server", async () => {
    const csrfError = '{"success":false,"error":{"code":"INVALID_CSRF_TOKEN","message":"CSRF"}}';
    const mismatch = '{"success":false,"error":{"code":"USER_MISMATCH","message":"사용자 불일치"}}';
    responses.push(() => raw(403, csrfError));
    responses.push(() => {
      globals.document = { cookie: "XSRF-TOKEN=token-of-new-account" };
      return new Response(null, { status: 204 });
    });
    responses.push(() => raw(409, mismatch));
    const result = await createMyReview("21", VIEWER, "A가 쓰던 글입니다", false);

    assert.deepEqual(
      calls.map((call) => `${call.method} ${call.url}`),
      ["POST /api/v1/me/reviews/21", "GET /api/v1/auth/csrf", "POST /api/v1/me/reviews/21"],
    );
    assert.equal(calls[2].headers["X-Expected-User-Id"], VIEWER);
    assert.equal(calls[2].headers["X-XSRF-TOKEN"], "token-of-new-account");
    assert.equal(result.kind === "error" ? result.code : null, "USER_MISMATCH");
  });

  it("does not refresh CSRF or retry on USER_MISMATCH", async () => {
    for (const run of [
      () => createMyReview("21", VIEWER, "본문입니다", false),
      () => updateMyReview("21", VIEWER, { reviewId: "7", version: "0" }, "본문입니다", false),
      () => deleteMyReview("21", VIEWER, { reviewId: "7", version: "0" }),
    ]) {
      calls = [];
      responses.push(() => raw(409, '{"success":false,"error":{"code":"USER_MISMATCH","message":"사용자 불일치"}}'));
      const result = await run();
      assert.equal(calls.length, 1);
      assert.equal(result.kind === "error" ? result.code : null, "USER_MISMATCH");
    }
  });

  it("refuses to send without a valid expected user id", async () => {
    for (const bad of ["", "0", "01", "-1", "1.0", "9223372036854775808"]) {
      await assert.rejects(() => createMyReview("21", bad, "본문입니다", false), /invalid expected user id/, bad);
      await assert.rejects(() => fetchMyReview("21", bad), /invalid expected user id/, bad);
    }
    assert.equal(calls.length, 0);
  });
});
