/**
 * 표지 데이터가 없을 때 쓰는 제목 기반 타일(장식)의 글자.
 * - 대표 제목의 앞뒤 공백을 뺀 첫 글자(사용자가 한 글자로 보는 단위, grapheme)를 쓴다.
 * - 따옴표·괄호 같은 문장 부호와 기호로 시작하면 건너뛰고 첫 문자·숫자를 쓴다.
 * - 라틴 문자는 대문자로 바꾼다. 쓸 글자가 없으면 "?"
 * 타일은 aria-hidden 장식이므로 스크린 리더는 제목 링크만 읽는다.
 */
const LETTER_OR_NUMBER = /[\p{L}\p{N}]/u;

export function titleInitial(title: string): string {
  const text = title.trim();
  if (text === "") {
    return "?";
  }
  for (const grapheme of graphemes(text)) {
    if (LETTER_OR_NUMBER.test(grapheme)) {
      return grapheme.toLocaleUpperCase("en");
    }
  }
  return "?";
}

function graphemes(text: string): string[] {
  if (typeof Intl !== "undefined" && "Segmenter" in Intl) {
    const segmenter = new Intl.Segmenter("und", { granularity: "grapheme" });
    return Array.from(segmenter.segment(text), (segment) => segment.segment);
  }
  return Array.from(text);
}
