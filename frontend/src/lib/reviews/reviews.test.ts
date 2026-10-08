import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { parseApiBody } from "../api/parse-json";
import { workDetailHref } from "../works/params";
import {
  BODY_FORBIDDEN_CHARACTER,
  BODY_LENGTH,
  BODY_REQUIRED,
  REVIEW_MAX_RAW_LENGTH,
  checkReviewBody,
  codePointLength,
  javaStrip,
} from "./body";
import { reviewFailure } from "./messages";
import { type MyReview, isLongId, isLongVersion, toMyReviewResult, toPublicReviewPage } from "./model";
import { reconcileDelete, reconcileWrite, sameReview } from "./reconcile";
import { InvalidReviewTargetError, createRequestJson, deleteQuery, updateRequestJson } from "./serialize";
import { formatSeoulDateTime } from "./time";

const TEN = "가나다라마바사아자차";
const EMOJI = "😀";
const PAST_SAFE = "9007199254740993"; // 2^53 + 1: double 로는 9007199254740992 가 된다
const TWO_TO_53 = "9007199254740992";
const LONG_MAX = "9223372036854775807";

function reasonOf(raw: string): string | null {
  const result = checkReviewBody(raw);
  return result.ok ? null : result.reason;
}

describe("review body rules (backend ReviewBody 와 같음)", () => {
  it("checks 10..2,000 code points after LF unification and strip", () => {
    assert.equal(reasonOf(TEN), null);
    assert.equal(reasonOf("가나다라마바사아자"), BODY_LENGTH);
    assert.equal(reasonOf("a".repeat(2000)), null);
    assert.equal(reasonOf("a".repeat(2001)), BODY_LENGTH);
    assert.equal(reasonOf("      가나다라마바사아자      "), BODY_LENGTH);
    assert.equal(checkReviewBody("  \t\n　" + TEN + "　 \n").ok, true);
  });

  it("counts supplementary characters as one code point and does not normalize", () => {
    assert.equal(EMOJI.length, 2);
    assert.equal(reasonOf(EMOJI.repeat(10)), null);
    assert.equal(reasonOf(EMOJI.repeat(9)), BODY_LENGTH);
    assert.equal(reasonOf(EMOJI.repeat(2000)), null);
    assert.equal(reasonOf(EMOJI.repeat(2001)), BODY_LENGTH);
    const decomposed = "é".repeat(5); // 10 코드 포인트, NFC 로 바꾸지 않음
    const result = checkReviewBody(decomposed);
    assert.equal(result.ok && result.body, decomposed);
    assert.equal(codePointLength("👨‍👩"), 3);
  });

  it("unifies CRLF and CR to LF and keeps inner whitespace", () => {
    const result = checkReviewBody("첫 줄입니다.\r\n\r\n\t둘째 줄\r끝");
    assert.deepEqual(result, { ok: true, body: "첫 줄입니다.\n\n\t둘째 줄\n끝", length: 16 });
    assert.equal(reasonOf("가나다라\r\n마바사아자"), null); // CRLF 는 1자
  });

  it("requires non-blank text", () => {
    for (const raw of ["", " ", "   \t\n ", "　　", "\r\n\r\n", " "]) {
      assert.equal(reasonOf(raw), BODY_REQUIRED, JSON.stringify(raw));
    }
  });

  it("rejects control characters other than LF and TAB, even at the edges", () => {
    for (const codePoint of [0x00, 0x01, 0x0b, 0x0c, 0x1b, 0x1c, 0x1f, 0x7f, 0x80, 0x85, 0x9f]) {
      const c = String.fromCharCode(codePoint);
      assert.equal(reasonOf(TEN + c + TEN), BODY_FORBIDDEN_CHARACTER, codePoint.toString(16));
      assert.equal(reasonOf(c + TEN), BODY_FORBIDDEN_CHARACTER, codePoint.toString(16));
    }
    assert.equal(reasonOf(TEN + "\t" + TEN), null);
  });

  it("strips like Java String.strip, not JavaScript trim", () => {
    assert.equal(javaStrip(" " + TEN + " "), " " + TEN + " "); // NBSP 는 남는다
    assert.equal(javaStrip("﻿" + TEN), "﻿" + TEN);
    assert.equal(javaStrip(" 　 " + TEN + " "), TEN);
  });

  it("keeps HTML characters as typed and applies the raw length limit", () => {
    const html = "<script>alert('x')</script> &amp; <b>굵게</b>";
    const result = checkReviewBody(html);
    assert.equal(result.ok && result.body, html);
    assert.equal(reasonOf(" ".repeat(REVIEW_MAX_RAW_LENGTH - 10) + TEN), null);
    assert.equal(reasonOf(" ".repeat(REVIEW_MAX_RAW_LENGTH - 9) + TEN), BODY_LENGTH);
  });
});

describe("review ids and versions stay exact (lossless round trip)", () => {
  const myReviewJson = (id: string, workId: string, version: string) =>
    `{"success":true,"data":{"workId":${workId},"review":{"id":${id},"body":"그림체가 좋다.\\n결말은 아쉽다.","containsSpoiler":false,"version":${version},"createdAt":"2026-10-08T07:58:11.241412Z","updatedAt":"2026-10-08T07:58:11.241412Z","edited":false}}}`;

  for (const value of ["1", "21", TWO_TO_53, PAST_SAFE, LONG_MAX]) {
    it(`reads and writes id/version ${value} without rounding`, () => {
      const parsed = parseApiBody(myReviewJson(value, value, value)) as { data: unknown };
      const result = toMyReviewResult(parsed.data);
      assert.ok(result?.review);
      assert.equal(result.workId, value);
      assert.equal(result.review.id, value);
      assert.equal(result.review.version, value);

      // 쓰기: 같은 값이 JSON 숫자 원문 그대로 나간다
      const json = updateRequestJson({ reviewId: result.review.id, version: result.review.version }, "본문입니다", true);
      assert.ok(json.includes(`"reviewId":${value},`), json);
      assert.ok(json.includes(`"version":${value},`), json);
      // 서버(Jackson 정수 파싱)와 같은 방식으로 다시 읽으면 같은 값이다
      const reparsed = parseApiBody(json) as { reviewId: string; version: string };
      assert.equal(reparsed.reviewId, value);
      assert.equal(reparsed.version, value);
      assert.equal(deleteQuery({ reviewId: value, version: value }), `reviewId=${value}&version=${value}`);
    });
  }

  it("shows that JSON.parse would have lost the value", () => {
    assert.equal(String(JSON.parse(`{"version":${PAST_SAFE}}`).version), TWO_TO_53);
  });

  it("serializes ids and versions as JSON numbers, never strings", () => {
    const json = updateRequestJson({ reviewId: PAST_SAFE, version: "0" }, "본문", false);
    assert.equal(json, `{"reviewId":${PAST_SAFE},"version":0,"body":"본문","containsSpoiler":false}`);
    assert.equal(typeof JSON.parse(json).reviewId, "number");
  });

  it("rejects ids and versions that are not exact Long decimals", () => {
    for (const bad of ["", "0", "01", "-1", "+1", "1.0", "1e3", " 1", "1 ", "9223372036854775808", "abc"]) {
      assert.throws(() => updateRequestJson({ reviewId: bad, version: "0" }, "본문", false), InvalidReviewTargetError, bad);
      assert.throws(() => deleteQuery({ reviewId: bad, version: "0" }), InvalidReviewTargetError, bad);
    }
    for (const bad of ["", "-1", "00", "01", "1.5", "9223372036854775808"]) {
      assert.throws(() => updateRequestJson({ reviewId: "1", version: bad }, "본문", false), InvalidReviewTargetError, bad);
    }
    assert.equal(isLongId(LONG_MAX), true);
    assert.equal(isLongVersion("0"), true);
    assert.equal(isLongId(1 as unknown), false);
  });

  it("escapes body text as JSON and keeps HTML and newlines", () => {
    const body = "<b>\"따옴표\"</b>\n\\ 백슬래시  ";
    const json = createRequestJson(body, true);
    assert.deepEqual(JSON.parse(json), { body, containsSpoiler: true });
  });
});

describe("review payload shapes", () => {
  it("accepts an unwritten review and rejects numeric or malformed ids", () => {
    assert.deepEqual(toMyReviewResult({ workId: "21", review: null }), { workId: "21", review: null });
    assert.equal(toMyReviewResult({ workId: 21, review: null }), null);
    assert.equal(toMyReviewResult({ workId: "21" }), null);
    const review = {
      id: "7",
      body: "본문",
      containsSpoiler: true,
      version: "1",
      createdAt: "2026-10-08T00:00:00Z",
      updatedAt: "2026-10-08T00:00:00Z",
      edited: false,
    };
    assert.ok(toMyReviewResult({ workId: "21", review }));
    assert.equal(toMyReviewResult({ workId: "21", review: { ...review, version: 1 } }), null);
    assert.equal(toMyReviewResult({ workId: "21", review: { ...review, containsSpoiler: "true" } }), null);
  });

  it("reads a public page with exact ids, nullable nickname and numeric counters", () => {
    const text = `{"success":true,"data":{"content":[{"id":${PAST_SAFE},"author":{"nickname":"독자"},"body":"<b>본문</b>","containsSpoiler":true,"createdAt":"2026-10-08T00:00:00Z","updatedAt":"2026-10-08T01:00:00Z","edited":true},{"id":2,"author":{"nickname":null},"body":"두 번째","containsSpoiler":false,"createdAt":"2026-10-07T00:00:00Z","updatedAt":"2026-10-07T00:00:00Z","edited":false}],"page":0,"size":10,"totalElements":12,"totalPages":2,"hasNext":true}}`;
    const page = toPublicReviewPage((parseApiBody(text) as { data: unknown }).data);
    assert.ok(page);
    assert.equal(page.content[0].id, PAST_SAFE);
    assert.equal(page.content[0].authorNickname, "독자");
    assert.equal(page.content[0].body, "<b>본문</b>");
    assert.equal(page.content[1].authorNickname, null);
    assert.equal(page.totalElements, 12);
    assert.equal(page.totalPages, 2);
    assert.equal(toPublicReviewPage({ content: [{ id: "1" }], page: 0, size: 10, totalElements: 1, totalPages: 1, hasNext: false }), null);
  });
});

describe("conflict reconciliation (no automatic resend)", () => {
  const base: MyReview = {
    id: "7",
    body: "원래 리뷰 내용입니다.",
    containsSpoiler: false,
    version: "3",
    createdAt: "2026-10-08T00:00:00Z",
    updatedAt: "2026-10-08T00:00:00Z",
    edited: true,
  };
  const intended = { body: "고친 리뷰 내용입니다.", containsSpoiler: true };

  it("detects applied, not applied and diverged writes", () => {
    assert.equal(reconcileWrite(base, intended, { ...base, ...intended, version: "4" }), "applied");
    assert.equal(reconcileWrite(base, intended, base), "not-applied");
    assert.equal(reconcileWrite(base, intended, { ...base, version: "4", body: "다른 탭의 내용" }), "diverged");
    // 삭제 후 재작성: version 이 같아도 id 가 다르면 다른 리뷰다
    assert.equal(reconcileWrite(base, intended, { ...base, id: "8" }), "diverged");
    assert.equal(reconcileWrite(base, intended, null), "diverged");
    assert.equal(reconcileWrite(null, intended, null), "not-applied");
    assert.equal(reconcileWrite(null, intended, { ...base, ...intended, id: "9", version: "0" }), "applied");
  });

  it("detects applied, not applied and diverged deletes", () => {
    assert.equal(reconcileDelete(base, null), "applied");
    assert.equal(reconcileDelete(base, base), "not-applied");
    assert.equal(reconcileDelete(base, { ...base, version: "4" }), "diverged");
    assert.equal(reconcileDelete(base, { ...base, id: "8", version: "3" }), "diverged");
    assert.equal(sameReview(null, null), true);
  });
});

describe("review failure mapping", () => {
  const error = (status: number, code: string, fieldErrors: { field: string; reason: string }[] = []) => ({
    kind: "error" as const,
    status,
    code,
    message: "서버 메시지",
    fieldErrors,
    retryAfterSeconds: null,
  });

  it("maps backend error codes", () => {
    assert.deepEqual(reviewFailure({ kind: "unavailable" }), { kind: "unknown-outcome" });
    assert.deepEqual(reviewFailure(error(401, "UNAUTHORIZED")), { kind: "session-expired" });
    assert.deepEqual(reviewFailure(error(409, "REVIEW_ALREADY_EXISTS")), { kind: "already-exists" });
    assert.deepEqual(reviewFailure(error(409, "REVIEW_VERSION_CONFLICT")), { kind: "version-conflict" });
    assert.deepEqual(reviewFailure(error(404, "REVIEW_NOT_FOUND")), { kind: "review-missing" });
    assert.equal(reviewFailure(error(403, "INVALID_CSRF_TOKEN")).kind, "message");
    assert.equal(reviewFailure(error(500, "INTERNAL_SERVER_ERROR")).kind, "unknown-outcome");
    assert.equal(reviewFailure(error(503, "SERVICE_UNAVAILABLE")).kind, "unknown-outcome");
    assert.match((reviewFailure(error(404, "WORK_NOT_FOUND")) as { message: string }).message, /작품을 찾을 수 없습니다/);
    assert.deepEqual(
      reviewFailure(error(400, "INVALID_INPUT", [{ field: "body", reason: "본문 오류" }, { field: "containsSpoiler", reason: "스포일러 오류" }])),
      { kind: "field", body: "본문 오류", spoiler: "스포일러 오류", other: null },
    );
    assert.deepEqual(reviewFailure(error(400, "INVALID_INPUT", [{ field: "version", reason: "버전 오류" }])), {
      kind: "field",
      body: null,
      spoiler: null,
      other: "버전 오류",
    });
  });
});

describe("Asia/Seoul time display", () => {
  it("formats UTC timestamps in Seoul time regardless of the host time zone", () => {
    assert.equal(formatSeoulDateTime("2026-10-08T07:58:11.241412Z"), "2026년 10월 8일 16:58");
    // 날짜가 바뀌는 경계: UTC 15:00 = 서울 다음 날 00:00
    assert.equal(formatSeoulDateTime("2026-12-31T15:00:00Z"), "2027년 1월 1일 00:00");
    assert.equal(formatSeoulDateTime("2026-10-08T14:59:59Z"), "2026년 10월 8일 23:59");
  });
});

describe("detail links keep work list page and review page", () => {
  it("adds from and reviewPage only when greater than 1", () => {
    assert.equal(workDetailHref(PAST_SAFE, 1, 1), `/works/${PAST_SAFE}`);
    assert.equal(workDetailHref(PAST_SAFE, 3), `/works/${PAST_SAFE}?from=3`);
    assert.equal(workDetailHref(PAST_SAFE, 1, 2), `/works/${PAST_SAFE}?reviewPage=2`);
    assert.equal(workDetailHref(PAST_SAFE, 3, 2), `/works/${PAST_SAFE}?from=3&reviewPage=2`);
  });
});
