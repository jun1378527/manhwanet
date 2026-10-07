import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { PROXIED_COOKIE_NAMES, SESSION_COOKIE_NAMES, pickCookieHeader, readCookieValue } from "../auth/cookies";
import { toCurrentUser } from "../auth/types";
import { parseApiBody } from "./parse-json";
import {
  downstreamResponseHeaders,
  readLimitedBody,
  resolveUpstreamUrl,
  sameOriginLocation,
  upstreamRequestHeaders,
} from "./proxy-rules";

const BASE = "http://127.0.0.1:8080/api/v1";
const ORIGIN = "http://localhost:3000";

describe("resolveUpstreamUrl", () => {
  it("maps /api/v1/** below the fixed API_BASE_URL", () => {
    assert.equal(resolveUpstreamUrl(BASE, `${ORIGIN}/api/v1/auth/me`)?.href, `${BASE}/auth/me`);
    assert.equal(resolveUpstreamUrl(`${BASE}/`, `${ORIGIN}/api/v1/works?page=2&size=20`)?.href, `${BASE}/works?page=2&size=20`);
    assert.equal(
      resolveUpstreamUrl("https://api.internal:8443/base/api/v1", `${ORIGIN}/api/v1/works/9223372036854775807`)?.href,
      "https://api.internal:8443/base/api/v1/works/9223372036854775807",
    );
  });

  it("never lets the request choose another host", () => {
    for (const path of [
      "/api/v1//evil.example/x",
      "/api/v1/%2F%2Fevil.example",
      "/api/v1/..%2F..%2Fadmin",
      "/api/v1/%2e%2e/%2e%2e/actuator",
      "/api/v1/.%2e/x",
      "/api/v1/%5Cevil",
      "/api/v1/a%00b",
      "/api/v1/",
      "/api/v1",
      "/api/v2/works",
      "/other",
    ]) {
      const target = resolveUpstreamUrl(BASE, `${ORIGIN}${path}`);
      assert.equal(target, null, path);
    }
    // 쿼리에 URL 이 있어도 목적지는 그대로다
    assert.equal(resolveUpstreamUrl(BASE, `${ORIGIN}/api/v1/works?next=https://evil.example`)?.origin, "http://127.0.0.1:8080");
  });

  it("rejects an invalid base URL", () => {
    assert.equal(resolveUpstreamUrl("not a url", `${ORIGIN}/api/v1/works`), null);
  });
});

describe("request headers", () => {
  it("forwards only auth cookies and needed headers", () => {
    const incoming = new Headers({
      accept: "application/json",
      "content-type": "application/json",
      "x-xsrf-token": "token-value",
      cookie: "theme=dark; MANHWANET_SESSION=ABC123; XSRF-TOKEN=token-value; _ga=GA1.1; __Host-MANHWANET_SESSION=DEF",
      host: "localhost:3000",
      origin: "http://localhost:3000",
      authorization: "Bearer should-not-pass",
      "x-forwarded-host": "evil.example",
    });
    const headers = upstreamRequestHeaders(incoming);
    assert.equal(headers.get("cookie"), "MANHWANET_SESSION=ABC123; XSRF-TOKEN=token-value; __Host-MANHWANET_SESSION=DEF");
    assert.equal(headers.get("x-xsrf-token"), "token-value");
    assert.equal(headers.get("content-type"), "application/json");
    assert.equal(headers.get("host"), null);
    assert.equal(headers.get("origin"), null);
    assert.equal(headers.get("authorization"), null);
    assert.equal(headers.get("x-forwarded-host"), null);
  });

  it("omits the Cookie header when no auth cookie is present", () => {
    assert.equal(upstreamRequestHeaders(new Headers({ cookie: "theme=dark" })).get("cookie"), null);
  });
});

describe("response headers", () => {
  it("passes every Set-Cookie and same-origin Location", () => {
    const upstream = new Headers();
    upstream.append("set-cookie", "XSRF-TOKEN=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/; SameSite=Lax");
    upstream.append("set-cookie", "XSRF-TOKEN=new-token; Path=/; SameSite=Lax");
    upstream.append("set-cookie", "MANHWANET_SESSION=NEW; Path=/; HttpOnly; SameSite=Lax");
    upstream.set("location", "/api/v1/auth/me");
    upstream.set("content-type", "application/json");
    upstream.set("cache-control", "no-cache, no-store, max-age=0, must-revalidate");
    upstream.set("retry-after", "900");
    upstream.set("x-internal", "secret");

    const headers = downstreamResponseHeaders(upstream);
    assert.equal(headers.getSetCookie().length, 3);
    assert.match(headers.getSetCookie()[0], /^XSRF-TOKEN=; Max-Age=0/);
    assert.equal(headers.get("location"), "/api/v1/auth/me");
    assert.equal(headers.get("retry-after"), "900");
    assert.equal(headers.get("cache-control"), "no-cache, no-store, max-age=0, must-revalidate");
    assert.equal(headers.get("x-internal"), null);
  });

  it("drops absolute Location and adds no-store when missing", () => {
    const headers = downstreamResponseHeaders(new Headers({ location: "http://127.0.0.1:8080/api/v1/auth/me" }));
    assert.equal(headers.get("location"), null);
    assert.equal(headers.get("cache-control"), "private, no-store");
    assert.equal(downstreamResponseHeaders(new Headers({ location: "//evil.example" })).get("location"), null);
  });

  it("keeps /api/v1 paths and query after same-origin checks", () => {
    assert.equal(sameOriginLocation("/api/v1/auth/me"), "/api/v1/auth/me");
    assert.equal(sameOriginLocation("/api/v1/works?page=2&size=20"), "/api/v1/works?page=2&size=20");
    assert.equal(
      downstreamResponseHeaders(new Headers({ location: "/api/v1/creators/1?from=list" })).get("location"),
      "/api/v1/creators/1?from=list",
    );
  });

  it("rejects backslash, control characters, external origins, and protocol-relative Location", () => {
    for (const location of [
      "/\\evil.example",
      "/\\\\evil.example",
      "///evil.example",
      "//evil.example",
      "http://evil.example/api/v1/auth/me",
      "https://evil.example/x",
      "/api/v1/auth/me\n",
      "/\t/evil.example",
      "/\u0000api",
      "/\u0001evil",
      "/\u007f",
      "/api/v1/a\\b",
      "javascript:alert(1)",
    ]) {
      assert.equal(sameOriginLocation(location), null, JSON.stringify(location));
    }
    for (const location of ["/\\evil.example", "//evil.example", "https://evil.example/x", "/api/v1/a\\b"]) {
      assert.equal(downstreamResponseHeaders(new Headers({ location })).get("location"), null, JSON.stringify(location));
    }
  });
});

describe("readLimitedBody", () => {
  function stream(...chunks: string[]) {
    return new Blob(chunks).stream();
  }

  it("reads up to the limit", async () => {
    const body = await readLimitedBody(stream('{"email":', '"a@b.co"}'), 64);
    assert.equal(new TextDecoder().decode(body ?? new Uint8Array()), '{"email":"a@b.co"}');
    assert.equal((await readLimitedBody(null, 10))?.byteLength, 0);
  });

  it("returns null over the limit", async () => {
    assert.equal(await readLimitedBody(stream("x".repeat(11)), 10), null);
  });
});

describe("cookies", () => {
  it("picks session cookies only for server-side auth fetches", () => {
    assert.equal(pickCookieHeader("XSRF-TOKEN=t; MANHWANET_SESSION=S", SESSION_COOKIE_NAMES), "MANHWANET_SESSION=S");
    assert.equal(pickCookieHeader("XSRF-TOKEN=t", SESSION_COOKIE_NAMES), null);
    assert.equal(pickCookieHeader(null, PROXIED_COOKIE_NAMES), null);
    assert.equal(pickCookieHeader("bad; =x; MANHWANET_SESSION=a=b", PROXIED_COOKIE_NAMES), "MANHWANET_SESSION=a=b");
  });

  it("reads one cookie value from document.cookie", () => {
    assert.equal(readCookieValue("a=1; XSRF-TOKEN=abc-123; b=2", "XSRF-TOKEN"), "abc-123");
    assert.equal(readCookieValue("XXSRF-TOKEN=no", "XSRF-TOKEN"), null);
    assert.equal(readCookieValue("XSRF-TOKEN=", "XSRF-TOKEN"), null);
    assert.equal(readCookieValue("", "XSRF-TOKEN"), null);
  });
});

describe("user id precision", () => {
  it("keeps large user ids exact through the lossless parser", () => {
    for (const id of ["1", "9007199254740993", "9223372036854775807"]) {
      const body = parseApiBody(
        `{"success":true,"data":{"id":${id},"email":"reader@example.com","nickname":"독자","role":"USER"}}`,
      ) as { data: unknown };
      assert.deepEqual(toCurrentUser(body.data), { id, email: "reader@example.com", nickname: "독자", role: "USER" });
    }
  });

  it("rejects unexpected shapes", () => {
    assert.equal(toCurrentUser({ id: 1, email: "a", nickname: "b", role: "USER" }), null);
    assert.equal(toCurrentUser({ id: "1", email: "a", nickname: "b", role: "ROOT" }), null);
    assert.equal(toCurrentUser(null), null);
  });
});
