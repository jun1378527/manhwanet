/**
 * 리뷰 요청 직렬화. reviewId·version 은 무손실 십진수 문자열로 보관하고,
 * 요청 JSON 에서는 Number 변환이나 문자열 치환 없이 JSON 숫자로 쓴다.
 * - 값은 먼저 isLongId·isLongVersion 으로 검증한다(선행 0·부호·소수·지수 표기·Long 범위 밖 거절).
 * - lossless-json 의 LosslessNumber 로 감싸 stringify 하면 원문 숫자 그대로 출력된다(정밀도 손실 없음).
 * - 백엔드는 문자열 reviewId·version 을 거절하므로(JSON 정수만 허용) 문자열로 보내지 않는다.
 */
import { LosslessNumber, stringify } from "lossless-json";

import { isLongId, isLongVersion } from "./model";

export type ReviewTarget = { reviewId: string; version: string };

export class InvalidReviewTargetError extends Error {
  constructor() {
    super("invalid review id or version");
    this.name = "InvalidReviewTargetError";
  }
}

function requireTarget(target: ReviewTarget): void {
  if (!isLongId(target.reviewId) || !isLongVersion(target.version)) {
    throw new InvalidReviewTargetError();
  }
}

function toJson(value: Record<string, unknown>): string {
  const text = stringify(value);
  if (text === undefined) {
    throw new Error("review request could not be serialized");
  }
  return text;
}

/** POST 본문 */
export function createRequestJson(body: string, containsSpoiler: boolean): string {
  return toJson({ body, containsSpoiler });
}

/** PUT 본문: {"reviewId":<숫자>,"version":<숫자>,"body":"…","containsSpoiler":…} */
export function updateRequestJson(target: ReviewTarget, body: string, containsSpoiler: boolean): string {
  requireTarget(target);
  return toJson({
    reviewId: new LosslessNumber(target.reviewId),
    version: new LosslessNumber(target.version),
    body,
    containsSpoiler,
  });
}

/** DELETE query: reviewId=…&version=… (요청 본문 없음) */
export function deleteQuery(target: ReviewTarget): string {
  requireTarget(target);
  return new URLSearchParams({ reviewId: target.reviewId, version: target.version }).toString();
}
