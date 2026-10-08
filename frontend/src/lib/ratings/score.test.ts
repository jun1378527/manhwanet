import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { ratingFailure } from "./messages";
import {
  NO_RATINGS_TEXT,
  RATING_SCORE_HALVES,
  describeSummary,
  formatScore,
  scoreFromHalf,
  toMyRating,
  toRatingSummary,
  toScoreHalf,
} from "./score";

describe("score rules (backend RatingScore)", () => {
  it("offers exactly 0.5 to 5.0 in 0.5 steps, no zero", () => {
    assert.deepEqual(
      RATING_SCORE_HALVES.map((half) => formatScore(scoreFromHalf(half))),
      ["0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"],
    );
  });

  it("accepts only half steps within range", () => {
    assert.equal(toScoreHalf(0.5), 1);
    assert.equal(toScoreHalf(5), 10);
    assert.equal(toScoreHalf(4.5), 9);
    for (const invalid of [0, -0.5, 5.5, 4.25, 0.49, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.equal(toScoreHalf(invalid), null, String(invalid));
    }
  });

  it("formats with one decimal place", () => {
    assert.equal(formatScore(4), "4.0");
    assert.equal(formatScore(4.3), "4.3");
    assert.equal(formatScore(0.5), "0.5");
  });
});

describe("public summary", () => {
  it("describes average and participants, or no ratings", () => {
    assert.equal(describeSummary({ average: 4.3, count: 1234 }), "평균 별점 5점 만점에 4.3점, 1,234명 참여");
    assert.equal(describeSummary({ average: null, count: 0 }), NO_RATINGS_TEXT);
    assert.equal(NO_RATINGS_TEXT, "아직 평가가 없습니다");
  });

  it("validates the summary shape", () => {
    assert.deepEqual(toRatingSummary({ average: 4.3, count: 2 }), { average: 4.3, count: 2 });
    assert.deepEqual(toRatingSummary({ average: null, count: 0 }), { average: null, count: 0 });
    assert.equal(toRatingSummary({ average: 4.3, count: 0 }), null);
    assert.equal(toRatingSummary({ average: null, count: 2 }), null);
    assert.equal(toRatingSummary({ average: 6, count: 1 }), null);
    assert.equal(toRatingSummary({ average: "4.3", count: 1 }), null);
    assert.equal(toRatingSummary({ average: 4, count: 1.5 }), null);
    assert.equal(toRatingSummary(undefined), null);
  });
});

describe("my rating shape", () => {
  const at = "2026-10-07T00:00:00Z";

  it("accepts rated and unrated responses with string workId", () => {
    assert.deepEqual(toMyRating({ workId: "9007199254740993", score: 4.5, createdAt: at, updatedAt: at }), {
      workId: "9007199254740993",
      score: 4.5,
      createdAt: at,
      updatedAt: at,
    });
    assert.deepEqual(toMyRating({ workId: "1", score: null, createdAt: null, updatedAt: null }), {
      workId: "1",
      score: null,
      createdAt: null,
      updatedAt: null,
    });
  });

  it("rejects numeric workId, invalid scores and inconsistent timestamps", () => {
    assert.equal(toMyRating({ workId: 1, score: 4.5, createdAt: at, updatedAt: at }), null);
    assert.equal(toMyRating({ workId: "1", score: 4.25, createdAt: at, updatedAt: at }), null);
    assert.equal(toMyRating({ workId: "1", score: 0, createdAt: at, updatedAt: at }), null);
    assert.equal(toMyRating({ workId: "1", score: 4.5, createdAt: null, updatedAt: null }), null);
    assert.equal(toMyRating({ workId: "1", score: null, createdAt: at, updatedAt: null }), null);
  });
});

describe("ratingFailure", () => {
  const error = (status: number, code: string | undefined, fieldErrors = [] as { field: string; reason: string }[]) => ({
    kind: "error" as const,
    status,
    code,
    message: "서버 메시지",
    fieldErrors,
    retryAfterSeconds: null,
  });

  it("maps outcomes", () => {
    assert.deepEqual(ratingFailure({ kind: "unavailable" }), { kind: "unknown-outcome" });
    assert.deepEqual(ratingFailure(error(409, "USER_MISMATCH")), { kind: "account-switched" });
    assert.deepEqual(ratingFailure(error(401, "UNAUTHORIZED")), { kind: "session-expired" });
    assert.equal(ratingFailure(error(403, "INVALID_CSRF_TOKEN")).kind, "message");
    assert.deepEqual(ratingFailure(error(400, "INVALID_INPUT", [{ field: "score", reason: "별점은 숫자여야 합니다." }])), {
      kind: "field",
      message: "별점은 숫자여야 합니다.",
    });
    assert.match(
      (ratingFailure(error(404, "WORK_NOT_FOUND")) as { message: string }).message,
      /작품을 찾을 수 없습니다/,
    );
    assert.match((ratingFailure(error(500, "INTERNAL_SERVER_ERROR")) as { message: string }).message, /처리하지 못했습니다/);
  });
});
