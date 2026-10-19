import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { workDetailHref } from "./params";
import {
  DEFAULT_WORKS_QUERY,
  WORK_SORTS,
  WORK_SORT_LABELS,
  WORK_SORT_NOTES,
  type WorksQuery,
  activeFilterCount,
  changeWorksQuery,
  hasConditions,
  normalizeQuery,
  parseWorksBack,
  parseWorksQuery,
  resolveReturnQuery,
  searchParamsToRecord,
  workDetailHrefFor,
  withPage,
  worksApiQuery,
  worksSearchHref,
} from "./search";

const q = (patch: Partial<WorksQuery>): WorksQuery => ({ ...DEFAULT_WORKS_QUERY, ...patch });

describe("검색어 정규화", () => {
  it("앞뒤 공백을 지우고 연속 공백·제어 문자를 공백 하나로", () => {
    assert.equal(normalizeQuery("  달빛   상점 "), "달빛 상점");
    assert.equal(normalizeQuery("\t달빛\n\u0000상점\u2028"), "달빛 상점");
    assert.equal(normalizeQuery("a\u00a0\u00a0b"), "a b");
    assert.equal(normalizeQuery("   "), "");
  });

  it("조합형 한글은 완성형으로, 특수문자는 그대로", () => {
    assert.equal(normalizeQuery("김하늘".normalize("NFD")), "김하늘");
    assert.equal(normalizeQuery("100% _\\ 'x' <b>"), "100% _\\ 'x' <b>");
  });

  it("100자(코드 포인트)를 넘으면 자른다", () => {
    assert.equal(Array.from(normalizeQuery("가".repeat(150))).length, 100);
    assert.equal(Array.from(normalizeQuery("𠀋".repeat(101))).length, 100);
    assert.equal(normalizeQuery(`${"가".repeat(99)} 나`), `${"가".repeat(99)}`);
  });
});

describe("URL → 화면 상태", () => {
  it("값이 없으면 기본값", () => {
    assert.deepEqual(parseWorksQuery({}), { query: DEFAULT_WORKS_QUERY, changed: false });
  });

  it("모든 조건을 읽는다", () => {
    const parsed = parseWorksQuery({ q: "하늘", format: "PAGE_COMIC", platform: "NAVER_WEBTOON", sort: "ratingCount", page: "3" });
    assert.deepEqual(parsed, {
      query: { q: "하늘", format: "PAGE_COMIC", platform: "NAVER_WEBTOON", sort: "ratingCount", page: 3 },
      changed: false,
    });
  });

  it("기본값을 직접 적은 값은 고칠 필요가 없다(폼 제출 주소)", () => {
    const parsed = parseWorksQuery({ q: "", format: "", platform: "", sort: "recent", page: "1" });
    assert.deepEqual(parsed, { query: DEFAULT_WORKS_QUERY, changed: false });
  });

  it("잘못된 값·중복 값·공백이 있는 검색어는 버리거나 고치고 changed", () => {
    const cases: [Record<string, string | string[]>, WorksQuery][] = [
      [{ q: "  하늘 " }, q({ q: "하늘" })],
      [{ q: "   " }, DEFAULT_WORKS_QUERY],
      [{ q: ["a", "b"] }, DEFAULT_WORKS_QUERY],
      [{ format: "webtoon" }, DEFAULT_WORKS_QUERY],
      [{ format: "MANGA", sort: "reviewCount" }, q({ sort: "reviewCount" })],
      // 이전 제목순은 더 이상 없다
      [{ sort: "title" }, DEFAULT_WORKS_QUERY],
      [{ platform: "naver" }, DEFAULT_WORKS_QUERY],
      [{ platform: "N" }, DEFAULT_WORKS_QUERY],
      [{ sort: "hot" }, DEFAULT_WORKS_QUERY],
      [{ sort: "RATING" }, DEFAULT_WORKS_QUERY],
      [{ page: "0", q: "x" }, q({ q: "x" })],
      [{ page: "abc" }, DEFAULT_WORKS_QUERY],
      [{ page: "2147483648" }, DEFAULT_WORKS_QUERY],
      [{ page: ["2", "3"] }, DEFAULT_WORKS_QUERY],
    ];
    for (const [params, expected] of cases) {
      assert.deepEqual(parseWorksQuery(params), { query: expected, changed: true }, JSON.stringify(params));
    }
  });

  it("모르는 파라미터는 무시한다", () => {
    assert.deepEqual(parseWorksQuery({ utm: "x", from: "2" }), { query: DEFAULT_WORKS_QUERY, changed: false });
  });
});

describe("화면 상태 → URL", () => {
  it("기본값은 생략하고 순서를 고정한다", () => {
    assert.equal(worksSearchHref(DEFAULT_WORKS_QUERY), "/works");
    assert.equal(worksSearchHref(q({ page: 1, sort: "recent" })), "/works");
    assert.equal(
      worksSearchHref({ page: 2, sort: "popular", platform: "RIDI", format: "WEBTOON", q: "달빛 상점" }),
      "/works?q=%EB%8B%AC%EB%B9%9B+%EC%83%81%EC%A0%90&format=WEBTOON&platform=RIDI&sort=popular&page=2",
    );
  });

  it("특수문자 검색어도 왕복하면 같은 상태", () => {
    for (const text of ["100% 진심", "a&b=c", "#해시?", "+plus+", "'O' \"x\"", "슬래시/역\\슬래시"]) {
      const state = q({ q: normalizeQuery(text), sort: "rating", page: 4 });
      const href = worksSearchHref(state);
      const url = new URL(href, "http://x.test");
      assert.equal(url.pathname, "/works");
      assert.deepEqual(parseWorksQuery(searchParamsToRecord(url.searchParams)), { query: state, changed: false }, text);
    }
  });

  it("조건을 바꾸면 첫 페이지, 페이지만 바꾸면 조건 유지", () => {
    const state = q({ q: "하늘", format: "WEBTOON", page: 5 });
    assert.deepEqual(changeWorksQuery(state, { sort: "ratingCount" }), { ...state, sort: "ratingCount", page: 1 });
    assert.deepEqual(changeWorksQuery(state, { q: "  구름  " }), { ...state, q: "구름", page: 1 });
    assert.deepEqual(changeWorksQuery(state, { format: null, platform: null }), { ...state, format: null, page: 1 });
    assert.deepEqual(withPage(state, 2), { ...state, page: 2 });
  });

  it("조건 여부와 필터 수", () => {
    assert.equal(hasConditions(DEFAULT_WORKS_QUERY), false);
    assert.equal(hasConditions(q({ sort: "popular", page: 3 })), false);
    assert.equal(hasConditions(q({ q: "a" })), true);
    assert.equal(activeFilterCount(q({ format: "WEBTOON", platform: "RIDI", q: "a" })), 2);
  });
});

describe("백엔드 요청", () => {
  it("조건·정렬이 기본값이면 기존 목록 호출과 같다(order 없음)", () => {
    assert.equal(worksApiQuery(DEFAULT_WORKS_QUERY, 20), "page=0&size=20");
    assert.equal(worksApiQuery(q({ page: 3 }), 20), "page=2&size=20");
  });

  it("조건과 정렬을 보낸다. 화면 sort → API order", () => {
    const params = new URLSearchParams(worksApiQuery(q({ q: "a b", format: "PAGE_COMIC", platform: "RIDI", sort: "ratingCount", page: 2 }), 20));
    assert.deepEqual(Object.fromEntries(params), {
      page: "1",
      size: "20",
      q: "a b",
      format: "PAGE_COMIC",
      platform: "RIDI",
      order: "ratingCount",
    });
  });

  it("화면 sort 값은 API order 값과 같다", () => {
    for (const sort of ["popular", "ratingCount", "reviewCount", "rating"] as const) {
      assert.equal(new URLSearchParams(worksApiQuery(q({ sort }), 20)).get("order"), sort);
    }
  });
});

describe("정렬 기준", () => {
  it("인기순 · 별많은순 · 리뷰많은순 · 높은평점순 · 최신등록순, 기본값은 최신등록순", () => {
    assert.deepEqual(WORK_SORTS.map((s) => WORK_SORT_LABELS[s]), ["인기순", "별많은순", "리뷰많은순", "높은평점순", "최신등록순"]);
    assert.equal(DEFAULT_WORKS_QUERY.sort, "recent");
  });

  it("정렬마다 기준 안내가 있고, 집계 정렬은 해당 값이 없는 작품도 포함한다고 알린다", () => {
    assert.match(WORK_SORT_NOTES.popular, /최근 30일 동안 별점·리뷰를 작성하거나 수정한 독자 수/);
    assert.match(WORK_SORT_NOTES.ratingCount, /별점이 없는 작품도/);
    assert.match(WORK_SORT_NOTES.reviewCount, /리뷰가 없는 작품도/);
    assert.match(WORK_SORT_NOTES.rating, /별점이 없는 작품도/);
    assert.equal(new Set(Object.values(WORK_SORT_NOTES)).size, WORK_SORTS.length);
  });
});

describe("상세 → 목록 복귀", () => {
  const state = q({ q: "하늘", platform: "RIDI", sort: "reviewCount", page: 3 });

  it("목록 상태가 기본값이면 생략, 페이지만 다르면 이전과 같은 from", () => {
    assert.equal(workDetailHrefFor("12", DEFAULT_WORKS_QUERY), "/works/12");
    assert.equal(workDetailHrefFor("12", q({ page: 4 })), "/works/12?from=4");
    assert.equal(workDetailHrefFor("12", q({ page: 4 }), 2), "/works/12?from=4&reviewPage=2");
    // 홈·기존 링크 함수와 같은 주소
    assert.equal(workDetailHrefFor("12", q({ page: 4 }), 2), workDetailHref("12", 4, 2));
  });

  it("조건이 있으면 back 에 목록 주소를 담고, 큰 ID 는 문자열 그대로", () => {
    const href = workDetailHrefFor("9007199254740993", state, 2);
    const url = new URL(href, "http://x.test");
    assert.equal(url.pathname, "/works/9007199254740993");
    assert.equal(url.searchParams.get("back"), worksSearchHref(state));
    assert.equal(url.searchParams.get("reviewPage"), "2");
    assert.deepEqual(resolveReturnQuery(url.searchParams.get("back") ?? undefined, undefined), state);
  });

  it("back 은 같은 출처의 /works 주소만 허용", () => {
    for (const bad of [
      "https://evil.test/works?q=a",
      "//evil.test/works",
      "/\\evil.test/works",
      "/works/12",
      "/worksx?q=a",
      "/works#x",
      "/works?q=a#frag",
      "/login?returnTo=/works",
      "javascript:alert(1)",
      "/works\u0000",
      "",
      `/works?q=${"a".repeat(1100)}`,
    ]) {
      assert.equal(parseWorksBack(bad), null, bad);
    }
    assert.equal(parseWorksBack(["/works", "/works?page=2"]), null);
    assert.deepEqual(parseWorksBack("/works"), DEFAULT_WORKS_QUERY);
  });

  it("back 의 잘못된 값·모르는 값은 버린다", () => {
    assert.deepEqual(parseWorksBack("/works?q=%20%ED%95%98%EB%8A%98%20&sort=bad&platform=x&page=2&evil=1"), q({ q: "하늘", page: 2 }));
  });

  it("back 이 없거나 잘못되면 from, 그것도 없으면 첫 페이지", () => {
    assert.deepEqual(resolveReturnQuery(undefined, "3"), q({ page: 3 }));
    assert.deepEqual(resolveReturnQuery("https://evil.test/", "3"), q({ page: 3 }));
    assert.deepEqual(resolveReturnQuery(undefined, "abc"), DEFAULT_WORKS_QUERY);
    assert.deepEqual(resolveReturnQuery(worksSearchHref(state), "9"), state);
  });
});
