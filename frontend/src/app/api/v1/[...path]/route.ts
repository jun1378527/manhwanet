import { apiBaseUrl } from "@/lib/api/client";
import {
  MAX_REQUEST_BODY_BYTES,
  downstreamResponseHeaders,
  methodHasBody,
  proxyErrorBody,
  readLimitedBody,
  resolveUpstreamUrl,
  upstreamRequestHeaders,
} from "@/lib/api/proxy-rules";

/**
 * 브라우저의 같은 출처 `/api/v1/**` 요청을 서버 설정 API_BASE_URL 의 백엔드로 전달한다.
 * - 목적지는 API_BASE_URL 아래 경로로 고정된다. (요청이 호스트·스킴을 고를 수 없음)
 * - 인증 쿠키(세션, XSRF-TOKEN)와 필요한 헤더만 넘기고, Set-Cookie 는 그대로 돌려준다.
 * - Route Handler 는 기본적으로 캐시하지 않으며, 백엔드 요청도 `no-store` 로 보낸다.
 * - 요청 본문·쿠키·토큰은 로그에 남기지 않는다.
 */

const UPSTREAM_TIMEOUT_MS = 10_000;

const JSON_HEADERS = { "content-type": "application/json", "cache-control": "private, no-store" };

async function proxy(request: Request): Promise<Response> {
  const target = resolveUpstreamUrl(apiBaseUrl(), request.url);
  if (target === null) {
    return new Response(proxyErrorBody("NOT_FOUND", "요청한 API를 찾을 수 없습니다."), {
      status: 404,
      headers: JSON_HEADERS,
    });
  }

  const method = request.method;
  let body: Uint8Array | undefined;
  if (methodHasBody(method)) {
    const read = await readLimitedBody(request.body, MAX_REQUEST_BODY_BYTES);
    if (read === null) {
      return new Response(proxyErrorBody("INVALID_INPUT", "요청 본문이 너무 큽니다."), {
        status: 413,
        headers: JSON_HEADERS,
      });
    }
    body = read.byteLength > 0 ? read : undefined;
  }

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method,
      headers: upstreamRequestHeaders(request.headers),
      body: body as BodyInit | undefined,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch (error) {
    // 경로만 남긴다. (쿼리·본문·헤더에는 사용자 입력이 있을 수 있음)
    console.error(`[api-proxy] ${method} ${target.pathname} failed: ${describe(error)}`);
    return new Response(proxyErrorBody("SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다."), {
      status: 503,
      headers: JSON_HEADERS,
    });
  }

  const status = upstream.status;
  const noBody = status === 204 || status === 304 || method === "HEAD";
  return new Response(noBody ? null : upstream.body, {
    status,
    headers: downstreamResponseHeaders(upstream.headers),
  });
}

function describe(error: unknown): string {
  if (error instanceof Error) {
    const cause = (error as Error & { cause?: unknown }).cause;
    return cause instanceof Error ? `${error.name}: ${cause.message}` : error.name;
  }
  return "unknown error";
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
