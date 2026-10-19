import type { CurrentUser, SessionState } from "./types";

/**
 * 헤더(클라이언트 컴포넌트)에 넘기는 로그인 상태. 화면에 쓰는 값(id·닉네임·역할)만 담는다.
 * 서버 컴포넌트가 클라이언트 컴포넌트에 넘긴 값은 페이지 응답(RSC 페이로드)에 실리므로 이메일은 빼고 넘긴다.
 */
export type NavUser = Pick<CurrentUser, "id" | "nickname" | "role">;
export type NavSessionState = { status: "authenticated"; user: NavUser } | { status: "anonymous" } | { status: "unavailable" };

export function toNavSession(state: SessionState): NavSessionState {
  if (state.status !== "authenticated") {
    return state;
  }
  const { id, nickname, role } = state.user;
  return { status: "authenticated", user: { id, nickname, role } };
}

/** 헤더·푸터가 함께 쓰는 화면 상태. expired 는 서버가 모르는, 이 탭에서 본 세션 만료다. */
export type NavView = {
  session: NavSessionState;
  expired: boolean;
};

export type SessionDecision = {
  view: NavView;
  /** 서버가 그린 화면(내 활동·사용자별 데이터)을 다시 받을지. 헤더·푸터 문구는 view 만으로 맞춘다. */
  refresh: boolean;
};

export function sameNavSession(a: NavSessionState, b: NavSessionState): boolean {
  if (a.status !== b.status) {
    return false;
  }
  if (a.status !== "authenticated" || b.status !== "authenticated") {
    return true;
  }
  return a.user.id === b.user.id && a.user.nickname === b.user.nickname && a.user.role === b.user.role;
}

/**
 * 서버 props 를 클라이언트 상태에 맞춘다.
 * - 연결 실패(unavailable)는 로그아웃으로 바꾸지 않는다.
 * - 만료 안내가 떠 있는 동안 서버의 로그인 props(만료 전에 그린 같은 사용자 포함)로 되돌리지 않는다.
 * - 이미 로그인한 클라이언트를 늦게 도착한 익명 props 로 되돌리지 않는다. 만료·로그아웃은 확인 응답으로만 반영한다.
 */
export function adoptServerProps(current: NavView, server: NavSessionState): NavView {
  if (server.status !== "authenticated" || current.expired || sameNavSession(current.session, server)) {
    return current;
  }
  return { session: server, expired: false };
}

/** 이 탭에서 확인한 세션. 401 만 만료로 보고, 연결 실패는 지금 화면을 유지한다. */
export function applyClientSession(current: NavView, fetched: NavSessionState): SessionDecision {
  if (fetched.status === "unavailable") {
    return { view: current, refresh: false };
  }
  if (fetched.status === "anonymous") {
    if (current.session.status !== "authenticated") {
      return { view: current.session.status === "anonymous" ? current : { session: fetched, expired: false }, refresh: false };
    }
    return { view: { session: fetched, expired: true }, refresh: true };
  }
  if (sameNavSession(current.session, fetched) && !current.expired) {
    return { view: current, refresh: false };
  }
  const switched = current.session.status === "authenticated" && current.session.user.id !== fetched.user.id;
  const signedIn = current.session.status !== "authenticated" || current.expired;
  return { view: { session: fetched, expired: false }, refresh: switched || signedIn };
}

/** 사용자가 누른 로그아웃. 만료 안내를 띄우지 않는다. */
export function applyExplicitLogout(): NavView {
  return { session: { status: "anonymous" }, expired: false };
}
