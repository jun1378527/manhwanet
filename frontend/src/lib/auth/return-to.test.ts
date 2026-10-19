import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { DEFAULT_RETURN_TO, describeReturnTo, loginHref, safeReturnTo, signupHref } from "./return-to";

describe("safeReturnTo", () => {
  it("keeps same-site paths with query and hash", () => {
    assert.equal(safeReturnTo("/works"), "/works");
    assert.equal(safeReturnTo("/works?page=3"), "/works?page=3");
    assert.equal(safeReturnTo("/works/9223372036854775807?from=2"), "/works/9223372036854775807?from=2");
    assert.equal(safeReturnTo("/works#top"), "/works#top");
    assert.equal(safeReturnTo("/"), "/");
  });

  it("falls back to /works for missing or non-string values", () => {
    assert.equal(safeReturnTo(undefined), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo(null), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo(""), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo(["/works", "/other"]), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo(`/${"a".repeat(2048)}`), DEFAULT_RETURN_TO);
  });

  for (const value of [
    "https://evil.example/",
    "http:/evil.example",
    "javascript:alert(1)",
    "//evil.example",
    "//evil.example/works",
    "/\\evil.example",
    "\\\\evil.example",
    "/\\/evil.example",
    "/works\n//evil.example",
    "/\t/evil.example",
    "works",
    " /works",
    "evil.example/works",
  ]) {
    it(`rejects ${JSON.stringify(value)}`, () => {
      assert.equal(safeReturnTo(value), DEFAULT_RETURN_TO);
    });
  }

  it("keeps percent-encoded characters as part of a same-site path", () => {
    assert.equal(safeReturnTo("/%0a/evil"), "/%0a/evil");
    assert.equal(safeReturnTo("/%2F%2Fevil.example"), "/%2F%2Fevil.example");
  });

  it("does not return to auth screens or API routes", () => {
    assert.equal(safeReturnTo("/login"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/login?returnTo=/works"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/signup"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/signup/x"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/api/v1/auth/logout"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/loginhelp"), "/loginhelp");
  });

  it("normalizes dot segments without leaving the site", () => {
    assert.equal(safeReturnTo("/works/../login"), DEFAULT_RETURN_TO);
    assert.equal(safeReturnTo("/a/../works"), "/works");
    assert.equal(safeReturnTo("/../../works"), "/works");
  });
});

describe("loginHref / signupHref", () => {
  it("omits the default return path", () => {
    assert.equal(loginHref("/works"), "/login");
    assert.equal(signupHref("/works"), "/signup");
  });

  it("encodes the return path and drops unsafe values", () => {
    assert.equal(loginHref("/works?page=2"), "/login?returnTo=%2Fworks%3Fpage%3D2");
    assert.equal(signupHref("/works/5?from=3"), "/signup?returnTo=%2Fworks%2F5%3Ffrom%3D3");
    assert.equal(loginHref("//evil.example"), "/login");
    assert.equal(loginHref("/works", { expired: true }), "/login?reason=expired");
  });
});

describe("describeReturnTo", () => {
  it("주소 대신 화면 종류로 알려 준다", () => {
    assert.equal(describeReturnTo("/"), "대문");
    assert.equal(describeReturnTo("/works"), "작품 목록");
    assert.equal(describeReturnTo("/works?q=%EB%8B%AC&sort=rating"), "보던 작품 찾기 결과");
    assert.equal(describeReturnTo("/works/9223372036854775807?back=%2Fworks%3Fq%3Da"), "보던 작품 화면");
    assert.equal(describeReturnTo("/works/1#write-review"), "보던 작품 화면");
    assert.equal(describeReturnTo("/somewhere/else"), "보던 화면");
  });

  it("로그인·가입 전환 링크가 같은 복귀 주소를 유지한다(반복 전환에도 겹쳐 쌓이지 않음)", () => {
    const target = "/works/5?back=%2Fworks%3Fq%3D%EB%8B%AC%26page%3D2";
    const login = loginHref(target);
    const back = new URL(login, "http://x.test").searchParams.get("returnTo");
    assert.equal(back, target);
    const again = signupHref(safeReturnTo(back));
    assert.equal(new URL(again, "http://x.test").searchParams.get("returnTo"), target);
    // 로그인·가입 화면 자체는 복귀 주소가 되지 않는다
    assert.equal(safeReturnTo(login), DEFAULT_RETURN_TO);
  });
});
