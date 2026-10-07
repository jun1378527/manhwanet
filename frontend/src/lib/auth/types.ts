/**
 * 인증 응답 타입. backend com.manhwanet.user.dto.UserResponse 와 대응한다.
 */

export type UserRole = "USER" | "ADMIN";

export type CurrentUser = {
  /** 백엔드 Long. JSON 숫자 정밀도 손실을 피하려고 원문 십진수 문자열로 둔다. (parseApiBody) */
  id: string;
  email: string;
  nickname: string;
  role: UserRole;
};

/**
 * 현재 로그인 상태.
 * - unavailable: 백엔드에 연결하지 못했거나 예상하지 못한 응답. 로그아웃 상태로 취급하지 않는다.
 */
export type SessionState =
  | { status: "authenticated"; user: CurrentUser }
  | { status: "anonymous" }
  | { status: "unavailable" };

/** 파싱된 응답 data 가 UserResponse 모양인지 확인한다. */
export function toCurrentUser(data: unknown): CurrentUser | null {
  if (typeof data !== "object" || data === null) {
    return null;
  }
  const value = data as Record<string, unknown>;
  if (
    typeof value.id !== "string" ||
    !/^[1-9]\d*$/.test(value.id) ||
    typeof value.email !== "string" ||
    typeof value.nickname !== "string" ||
    (value.role !== "USER" && value.role !== "ADMIN")
  ) {
    return null;
  }
  return { id: value.id, email: value.email, nickname: value.nickname, role: value.role };
}
