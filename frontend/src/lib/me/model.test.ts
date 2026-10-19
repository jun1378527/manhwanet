import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { parseApiBody } from "@/lib/api/parse-json";

import { DEFAULT_ME_QUERY, meHref, parseMeQuery, reviewEditHref, toActivitySummary, toMyRatingItem, toMyReviewItem, toPage } from "./model";

const work = { id: "9007199254740993", workFormat: "WEBTOON", primaryTitle: { title: "달빛 상점", languageCode: "ko" }, creators: [{ creatorId: "1", name: "가상작가", role: "STORY" }] };

describe("/me 주소", () => {
  it("기본값은 생략, tab·page 를 고정 순서로", () => {
    assert.equal(meHref(DEFAULT_ME_QUERY), "/me");
    assert.equal(meHref({ tab: "ratings", page: 3 }), "/me?page=3");
    assert.equal(meHref({ tab: "reviews", page: 1 }), "/me?tab=reviews");
    assert.equal(meHref({ tab: "reviews", page: 2 }), "/me?tab=reviews&page=2");
  });

  it("잘못된 값·중복 값은 버리고 changed, 기본값을 적은 값은 그대로", () => {
    assert.deepEqual(parseMeQuery({}), { query: DEFAULT_ME_QUERY, changed: false });
    assert.deepEqual(parseMeQuery({ tab: "ratings", page: "1" }), { query: DEFAULT_ME_QUERY, changed: false });
    assert.deepEqual(parseMeQuery({ tab: "reviews", page: "4" }), { query: { tab: "reviews", page: 4 }, changed: false });
    const bad: Record<string, string | string[]>[] = [{ tab: "admin" }, { tab: ["ratings", "reviews"] }, { page: "0" }, { page: "x" }, { page: ["1", "2"] }];
    for (const params of bad) {
      assert.equal(parseMeQuery(params).changed, true, JSON.stringify(params));
    }
    assert.deepEqual(parseMeQuery({ tab: "reviews", page: "x" }).query, { tab: "reviews", page: 1 });
  });

  it("리뷰 수정은 작품 상세의 내 리뷰로", () => {
    assert.equal(reviewEditHref("9007199254740993"), "/works/9007199254740993#my-review");
  });
});

describe("응답 해석", () => {
  it("큰 ID 를 문자열 그대로(무손실 파서)", () => {
    const body = parseApiBody(
      '{"workId":9007199254740993,"available":true,"work":{"id":9007199254740993,"workFormat":"WEBTOON","primaryTitle":{"title":"달빛 상점","languageCode":"ko"},"creators":[{"creatorId":1,"name":"가상작가","role":"STORY"}]},"score":4.5,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z"}',
    );
    const item = toMyRatingItem(body);
    assert.equal(item?.workId, "9007199254740993");
    assert.equal(item?.work?.id, "9007199254740993");
    assert.equal(item?.score, 4.5);
  });

  it("공개되지 않는 작품은 작품 정보 없이, 공개 여부와 작품 정보가 어긋나면 거절", () => {
    const base = { workId: "5", score: 4, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z" };
    assert.deepEqual(toMyRatingItem({ ...base, available: false, work: null })?.work, null);
    assert.equal(toMyRatingItem({ ...base, available: false, work: { ...work, id: "5" } }), null);
    assert.equal(toMyRatingItem({ ...base, available: true, work: null }), null);
    assert.equal(toMyRatingItem({ ...base, available: true, work }), null, "작품 ID 불일치");
    assert.equal(toMyRatingItem({ ...base, available: false, work: null, score: 7 }), null);
  });

  it("리뷰 항목과 페이지·요약", () => {
    const review = {
      id: "10",
      workId: work.id,
      available: true,
      work,
      excerpt: "본문",
      excerptTruncated: false,
      containsSpoiler: true,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-01T00:00:00Z",
      edited: false,
    };
    assert.equal(toMyReviewItem(review)?.containsSpoiler, true);
    assert.equal(toMyReviewItem({ ...review, excerpt: 1 }), null);
    const page = toPage({ content: [review], page: 0, size: 20, totalElements: 1, totalPages: 1 }, toMyReviewItem);
    assert.equal(page?.content.length, 1);
    assert.equal(toPage({ content: [review, { bad: true }], page: 0, size: 20, totalElements: 2, totalPages: 1 }, toMyReviewItem), null);
    assert.deepEqual(toActivitySummary({ ratingCount: 2, reviewCount: 0 }), { ratingCount: 2, reviewCount: 0 });
    assert.equal(toActivitySummary({ ratingCount: -1, reviewCount: 0 }), null);
  });
});
