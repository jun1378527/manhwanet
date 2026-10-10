import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { countryLabel, countryName } from "./labels";
import { titleInitial } from "./tile";

describe("title tile initial", () => {
  it("uses the first character of Korean, Japanese and Latin titles", () => {
    assert.equal(titleInitial("달빛 기사단"), "달");
    assert.equal(titleInitial("灯台守のレシピ"), "灯");
    assert.equal(titleInitial("the lighthouse"), "T");
    assert.equal(titleInitial("  백 번째 봄  "), "백");
  });

  it("skips leading punctuation and symbols", () => {
    assert.equal(titleInitial("「雨宿りの怪獣」"), "雨");
    assert.equal(titleInitial("《회색 도시》"), "회");
    assert.equal(titleInitial("...and then"), "A");
    assert.equal(titleInitial("#1 기사"), "1");
  });

  it("keeps a whole grapheme and falls back to ?", () => {
    assert.equal(titleInitial("가́나"), "가́");
    assert.equal(titleInitial("𠀋の話"), "𠀋");
    assert.equal(titleInitial(""), "?");
    assert.equal(titleInitial("   "), "?");
    assert.equal(titleInitial("!!!"), "?");
  });
});

describe("country labels", () => {
  it("shows a name only for cards and name with code for details", () => {
    assert.equal(countryName("KR"), "대한민국");
    assert.equal(countryName("JP"), "일본");
    assert.equal(countryLabel("JP"), "일본 (JP)");
  });

  it("falls back to the code", () => {
    assert.equal(countryName("AA"), "AA");
  });
});
