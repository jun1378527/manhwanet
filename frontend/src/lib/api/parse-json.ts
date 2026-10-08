import { isLosslessNumber, parse } from "lossless-json";

const EXACT_DECIMAL_KEYS = new Set(["id", "creatorId", "workId", "reviewId", "version"]);

/**
 * API JSON 본문을 읽는다.
 * id·creatorId·workId·reviewId·version(백엔드 Long)은 원문 십진수 문자열로 두고,
 * 페이지 수·별점 같은 다른 숫자는 JavaScript number로 둔다.
 * (리뷰 version 은 수정할 때마다 1씩 늘어 2^53 을 넘을 수 있는 Long 으로 취급한다)
 */
export function parseApiBody(text: string): unknown {
  return parse(text, (key, value) => {
    if (!isLosslessNumber(value)) {
      return value;
    }

    const digits = value.toString();
    if (EXACT_DECIMAL_KEYS.has(key)) {
      return digits;
    }

    return Number(digits);
  });
}
