import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  javaStrip,
  passwordLength,
  validateEmail,
  validateLogin,
  validateNewPassword,
  validateNickname,
  validateSignup,
} from "./validation";

describe("password policy (backend Passwords/PasswordPolicy)", () => {
  it("counts code points after NFKC", () => {
    assert.equal(passwordLength("abc"), 3);
    assert.equal(passwordLength("😀😀"), 2); // 서로게이트 쌍도 1자
    assert.equal(passwordLength("ｐｌｕｍ"), 4); // 전각 → 반각
    assert.equal(passwordLength("가"), 1); // 조합형 한글 → 완성형 1자
    assert.equal(passwordLength("ﬁ"), 2); // 합자 → f + i
  });

  it("accepts 15 to 128 characters without trimming", () => {
    assert.equal(validateNewPassword("a".repeat(15)), undefined);
    assert.equal(validateNewPassword("a".repeat(128)), undefined);
    assert.equal(validateNewPassword(" ".repeat(15)), undefined);
    assert.equal(validateNewPassword("😀".repeat(15)), undefined);
    assert.match(validateNewPassword("a".repeat(14)) ?? "", /15자 이상 128자 이하.*현재 14자/);
    assert.match(validateNewPassword("a".repeat(129)) ?? "", /현재 129자/);
    assert.match(validateNewPassword(" a".repeat(7)) ?? "", /현재 14자/);
    assert.equal(validateNewPassword(""), "비밀번호를 입력해 주세요.");
  });

  it("uses the NFKC length, not the raw UTF-16 length", () => {
    // 14자 + 합자 1개 = 원문 15 UTF-16 단위, NFKC 후 16자
    assert.equal(validateNewPassword(`${"a".repeat(14)}ﬁ`), undefined);
    // 조합형 한글 15음절 = 원문 30 UTF-16 단위, NFKC 후 15자
    assert.equal(validateNewPassword("가".repeat(15)), undefined);
    assert.notEqual(validateNewPassword("가".repeat(14)), undefined);
  });
});

describe("email (backend EmailAddresses)", () => {
  it("accepts what the backend accepts", () => {
    assert.equal(validateEmail("reader@example.com"), undefined);
    assert.equal(validateEmail("  Reader@Example.COM  "), undefined);
    assert.equal(validateEmail("a.b+c@sub.example.co.kr"), undefined);
  });

  it("rejects malformed emails", () => {
    assert.equal(validateEmail("   "), "이메일을 입력해 주세요.");
    assert.notEqual(validateEmail("reader"), undefined);
    assert.notEqual(validateEmail("reader@example"), undefined);
    assert.notEqual(validateEmail("a@b@example.com"), undefined);
    assert.notEqual(validateEmail("re ader@example.com"), undefined);
    assert.notEqual(validateEmail(`${"a".repeat(65)}@example.com`), undefined);
    assert.notEqual(validateEmail(`a@${"b".repeat(250)}.com`), undefined);
  });
});

describe("nickname (backend Nicknames)", () => {
  it("counts code points after strip", () => {
    assert.equal(validateNickname("독자"), undefined);
    assert.equal(validateNickname("  독자  "), undefined);
    assert.equal(validateNickname("😀😀"), undefined);
    assert.notEqual(validateNickname("독"), undefined);
    assert.notEqual(validateNickname(" 독 "), undefined);
    assert.equal(validateNickname("가".repeat(30)), undefined);
    assert.notEqual(validateNickname("가".repeat(31)), undefined);
  });

  it("strips like Java String.strip (NBSP is not whitespace)", () => {
    assert.equal(javaStrip("　독자 "), "독자");
    assert.equal(javaStrip(" 독자"), " 독자");
    assert.equal(javaStrip("\t\n독자\u001f"), "독자");
  });
});

describe("form validation", () => {
  it("collects signup errors per field", () => {
    assert.deepEqual(Object.keys(validateSignup({ email: "", nickname: "", password: "" })).sort(), [
      "email",
      "nickname",
      "password",
    ]);
    assert.deepEqual(
      validateSignup({ email: "reader@example.com", nickname: "독자", password: "correct horse battery" }),
      {},
    );
  });

  it("only checks presence on login", () => {
    assert.deepEqual(validateLogin({ email: "x", password: "short" }), {});
    assert.deepEqual(Object.keys(validateLogin({ email: " ", password: "" })).sort(), ["email", "password"]);
  });
});
