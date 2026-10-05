/**
 * 프론트엔드에서 사용하는 환경변수를 한곳에서 읽는다.
 * NEXT_PUBLIC_ 접두사가 붙은 값은 빌드 시점에 클라이언트 번들에 포함되므로 secret을 넣지 않는다.
 */
export const env = {
  apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1",
} as const;
