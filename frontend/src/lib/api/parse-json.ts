import { isLosslessNumber, parse } from "lossless-json";

const EXACT_DECIMAL_KEYS = new Set(["id", "creatorId"]);

/**
 * API JSON 본문을 읽는다.
 * 작품 id와 creatorId는 원문 십진수 문자열로 두고, 페이지 수 같은 다른 숫자는 JavaScript number로 둔다.
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
