import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { demoCoverFor } from "./demo-covers";
import { GENRE_MENU } from "./genres";
import { formatRegisteredDate, formatReviewTime, toRecentReview, toRecentReviews, withholdSpoilerBody } from "./model";

const review = {
  id: "9007199254740993",
  work: { id: "12", primaryTitle: { title: "작품", languageCode: "ko" } },
  author: { nickname: "독자" },
  body: "본문",
  containsSpoiler: true,
  createdAt: "2026-10-17T03:30:00Z",
  updatedAt: "2026-10-17T03:30:00Z",
  edited: false,
};

describe("장르 메뉴", () => {
  it("전체만 실제 목록으로 연결하고 나머지는 준비 중", () => {
    assert.equal(GENRE_MENU[0].label, "전체");
    assert.equal(GENRE_MENU[0].available && GENRE_MENU[0].href, "/works");
    assert.ok(GENRE_MENU.slice(1).every((g) => !g.available));
    assert.equal(GENRE_MENU.length, 14);
  });

  it("국가별(한국 웹툰·일본 만화) 구분 항목이 없다", () => {
    for (const g of GENRE_MENU) {
      assert.ok(!/웹툰|만화|한국|일본/.test(g.label), g.label);
    }
  });
});

describe("데모 표지", () => {
  it("데모 카탈로그의 정확한 제목에만 연결한다", () => {
    assert.equal(demoCoverFor({ title: "[DEMO] 가상의 별빛 정원", languageCode: "ko" }), "star-garden");
    assert.equal(demoCoverFor({ title: "[DEMO] 가상의 시계탑 소녀", languageCode: "ko" }), "clock-tower");
  });

  it("비슷한 제목·접두어·다른 언어에는 붙이지 않는다", () => {
    for (const title of ["[DEMO] 가상의 별빛 정원 2", "별빛 정원", "[DEMO] 다른 작품", " [DEMO] 가상의 달빛 서점", "[demo] 가상의 달빛 서점"]) {
      assert.equal(demoCoverFor({ title, languageCode: "ko" }), null, title);
    }
    assert.equal(demoCoverFor({ title: "[DEMO] 가상의 별빛 정원", languageCode: "ja" }), null);
  });
});

describe("최근 리뷰 응답", () => {
  it("올바른 응답을 변환한다(큰 ID는 문자열 유지)", () => {
    const r = toRecentReview(review);
    assert.ok(r);
    assert.equal(r.id, "9007199254740993");
    assert.equal(r.work.id, "12");
    assert.equal(r.authorNickname, "독자");
    assert.equal(r.containsSpoiler, true);
  });

  it("닉네임이 없으면 null", () => {
    assert.equal(toRecentReview({ ...review, author: { nickname: null } })?.authorNickname, null);
  });

  it("모양이 다르면 거절한다", () => {
    assert.equal(toRecentReview({ ...review, id: 12 }), null);
    assert.equal(toRecentReview({ ...review, work: { id: "1" } }), null);
    assert.equal(toRecentReview({ ...review, containsSpoiler: "true" }), null);
    assert.equal(toRecentReview({ ...review, createdAt: "not a date" }), null);
    assert.equal(toRecentReviews({}), null);
    assert.equal(toRecentReviews([review, { ...review, body: 1 }]), null);
    assert.deepEqual(toRecentReviews([]), []);
  });
});

describe("스포일러 본문 보류", () => {
  it("스포일러 리뷰는 화면에 넘길 때 본문을 뺀다", () => {
    const r = toRecentReview(review)!;
    assert.equal(withholdSpoilerBody(r).body, null);
    assert.ok(!JSON.stringify(withholdSpoilerBody(r)).includes("본문"));
    const plain = toRecentReview({ ...review, containsSpoiler: false })!;
    assert.equal(withholdSpoilerBody(plain).body, "본문");
  });
});

describe("시각 표시 (Asia/Seoul)", () => {
  it("리뷰 시각과 등록일", () => {
    assert.equal(formatReviewTime("2026-10-17T03:30:00Z"), "10월 17일 12:30");
    assert.equal(formatReviewTime("2026-10-16T15:05:00Z"), "10월 17일 00:05");
    assert.equal(formatRegisteredDate("2026-10-16T15:05:00Z"), "2026.10.17");
    assert.equal(formatRegisteredDate("2026-01-02T00:00:00Z"), "2026.01.02");
  });
});
