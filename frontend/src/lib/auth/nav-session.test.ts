import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  type NavUser,
  type NavView,
  adoptServerProps,
  applyClientSession,
  applyExplicitLogout,
} from "./nav-session";

const userA: NavUser = { id: "4", nickname: "검증계정가", role: "USER" };
const userB: NavUser = { id: "5", nickname: "검증계정이", role: "USER" };

function authed(user: NavUser, expired = false): NavView {
  return { session: { status: "authenticated", user }, expired };
}

function anonymous(expired = false): NavView {
  return { session: { status: "anonymous" }, expired };
}

describe("세션 만료와 다시 로그인", () => {
  it("만료를 감지하면 헤더·푸터에 쓸 상태를 익명·만료로 맞추고 서버 화면을 다시 그린다", () => {
    const decision = applyClientSession(authed(userA), { status: "anonymous" });
    assert.deepEqual(decision.view, anonymous(true));
    assert.equal(decision.refresh, true);
  });

  it("같은 사용자 ID로 다시 로그인하면 만료 안내를 지우고 닉네임을 되돌린다", () => {
    const decision = applyClientSession(anonymous(true), { status: "authenticated", user: userA });
    assert.deepEqual(decision.view, authed(userA));
    assert.equal(decision.refresh, true);
  });

  it("만료 뒤 다른 계정으로 로그인하면 그 계정의 닉네임만 남긴다", () => {
    const decision = applyClientSession(anonymous(true), { status: "authenticated", user: userB });
    assert.deepEqual(decision.view, authed(userB));
    assert.equal(decision.refresh, true);
  });

  it("정상 로그아웃은 만료 안내 없이 익명이 되고, 다시 로그인하면 닉네임이 돌아온다", () => {
    const loggedOut = applyExplicitLogout();
    assert.deepEqual(loggedOut, anonymous(false));
    const again = applyClientSession(loggedOut, { status: "authenticated", user: userA });
    assert.deepEqual(again.view, authed(userA));
  });

  it("확인 API가 잠시 실패하면 로그인·만료 상태를 그대로 둔다", () => {
    const loggedIn = authed(userA);
    const expired = anonymous(true);
    assert.equal(applyClientSession(loggedIn, { status: "unavailable" }).view, loggedIn);
    assert.equal(applyClientSession(loggedIn, { status: "unavailable" }).refresh, false);
    assert.equal(applyClientSession(expired, { status: "unavailable" }).view, expired);
  });

  it("같은 사용자를 다시 확인하면 화면을 새로 그리지 않는다", () => {
    const current = authed(userA);
    const decision = applyClientSession(current, { status: "authenticated", user: userA });
    assert.equal(decision.view, current);
    assert.equal(decision.refresh, false);
  });

  it("다른 계정으로 바뀌면 그 사용자로 맞추고 서버 화면을 다시 그린다", () => {
    const decision = applyClientSession(authed(userA), { status: "authenticated", user: userB });
    assert.deepEqual(decision.view, authed(userB));
    assert.equal(decision.refresh, true);
  });
});

describe("서버 props 동기화", () => {
  it("만료 중에는 만료 전에 그린 같은 사용자 props 로 로그인 표시를 되돌리지 않는다", () => {
    const expired = anonymous(true);
    assert.equal(adoptServerProps(expired, { status: "authenticated", user: userA }), expired);
  });

  it("다시 로그인한 뒤에는 늦게 도착한 익명 props 가 닉네임을 지우지 않는다", () => {
    const signedIn = authed(userA);
    assert.equal(adoptServerProps(signedIn, { status: "anonymous" }), signedIn);
  });

  it("연결 실패 props 는 지금 사용자를 로그아웃으로 바꾸지 않는다", () => {
    const signedIn = authed(userA);
    assert.equal(adoptServerProps(signedIn, { status: "unavailable" }), signedIn);
  });

  it("만료가 아닌 익명 화면은 서버의 새 로그인 props 를 따른다", () => {
    assert.deepEqual(adoptServerProps(anonymous(false), { status: "authenticated", user: userB }), authed(userB));
  });
});
