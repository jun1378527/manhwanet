import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { type PlatformLink, editionLanguageLabel, reviewCountOf, safePlatformHref, visiblePlatformLinks } from "./detail";

const link = (patch: Partial<PlatformLink>): PlatformLink => ({
  platformCode: "NAVER_WEBTOON",
  platformName: "네이버웹툰",
  url: "https://comic.naver.com/webtoon/list?titleId=1",
  listingKind: "MAIN",
  languageCode: "ko",
  ...patch,
});

describe("리뷰 수", () => {
  it("응답에 있는 0 이상의 정수만 쓴다(이전 응답은 표시하지 않음)", () => {
    assert.equal(reviewCountOf({ reviewCount: 3 }), 3);
    assert.equal(reviewCountOf({ reviewCount: 0 }), 0);
    assert.equal(reviewCountOf({}), null);
    assert.equal(reviewCountOf({ reviewCount: -1 }), null);
    assert.equal(reviewCountOf({ reviewCount: 1.5 }), null);
    assert.equal(reviewCountOf({ reviewCount: "3" }), null);
  });
});

describe("플랫폼 바로가기 주소", () => {
  it("https 주소만, 사용자 정보·포트·프래그먼트·공백·역슬래시 거절", () => {
    assert.equal(safePlatformHref("https://comic.naver.com/webtoon/list?titleId=1"), "https://comic.naver.com/webtoon/list?titleId=1");
    for (const bad of [
      "http://comic.naver.com/x",
      "javascript:alert(1)",
      "data:text/html,x",
      "https://user:pw@comic.naver.com/x",
      "https://comic.naver.com:8443/x",
      "https://comic.naver.com/x#frag",
      "https://comic.naver.com/a b",
      "https://comic.naver.com/\\x",
      "https://localhost/x",
      "//comic.naver.com/x",
      "/works/1",
      "",
      null,
      42,
    ]) {
      assert.equal(safePlatformHref(bad), null, String(bad));
    }
  });

  it("안전한 연결만, 같은 주소는 한 번, 종류 이름을 붙이고 같은 이름은 번호로 구분한다", () => {
    const links = visiblePlatformLinks([
      link({}),
      link({ platformCode: "KAKAO_PAGE", platformName: "카카오페이지", url: "https://page.kakao.com/content/2", listingKind: "SEASON" }),
      link({ url: "javascript:alert(1)" }),
      link({ platformName: "  ", url: "https://x.example/1" }),
      link({}),
      link({ url: "https://comic.naver.com/webtoon/list?titleId=9" }),
    ]);
    assert.deepEqual(links.map((l) => [l.label, l.href]), [
      ["네이버웹툰", "https://comic.naver.com/webtoon/list?titleId=1"],
      ["카카오페이지 시즌", "https://page.kakao.com/content/2"],
      ["네이버웹툰 2", "https://comic.naver.com/webtoon/list?titleId=9"],
    ]);
    assert.deepEqual(visiblePlatformLinks(undefined), []);
    assert.deepEqual(visiblePlatformLinks("x"), []);
  });
});

describe("바로가기 언어 표시", () => {
  it("언어 이름 + 판, 모르는 태그는 그대로", () => {
    assert.equal(editionLanguageLabel("en"), "영어판");
    assert.equal(editionLanguageLabel("ja"), "일본어판");
    assert.equal(editionLanguageLabel("zz-invalid-!!"), "zz-invalid-!!");
  });
});
