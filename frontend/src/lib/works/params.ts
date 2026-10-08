/**
 * URL 파라미터 해석. 화면과 URL의 페이지 번호는 1부터 시작하고, 백엔드 API는 0부터 시작한다.
 */

/** 백엔드 page 파라미터가 Java int 이므로 그 범위를 넘지 않게 한다. */
const MAX_PAGE = 2_147_483_647;
/** 작품 ID 는 Java Long */
const MAX_ID = BigInt("9223372036854775807");

type SearchParamValue = string | string[] | undefined;

/**
 * ?page= 값을 1부터 시작하는 페이지 번호로 해석한다.
 * 값이 없으면 1, 형식이 잘못되었으면 null (호출한 쪽에서 정규 URL로 이동시킨다).
 */
export function parseListPage(value: SearchParamValue): number | null {
  if (value === undefined) {
    return 1;
  }
  if (Array.isArray(value) || !/^[1-9]\d{0,9}$/.test(value)) {
    return null;
  }
  const page = Number(value);
  return page <= MAX_PAGE ? page : null;
}

/** 상세 → 목록 복귀용 ?from= 값. 잘못된 값이면 1페이지로 돌아간다. */
export function parseReturnPage(value: SearchParamValue): number {
  return parseListPage(value) ?? 1;
}

/** 작품 ID 경로 파라미터 검증 (양의 정수, Long 범위) */
export function isValidWorkId(value: string): boolean {
  return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= MAX_ID;
}

export function worksListHref(page: number): string {
  return page > 1 ? `/works?page=${page}` : "/works";
}

/**
 * 작품 상세 주소. from(작품 목록 복귀 페이지)과 reviewPage(공개 리뷰 페이지, 1부터)는 1이면 생략한다.
 * 순서는 from → reviewPage 로 고정한다.
 */
export function workDetailHref(id: string, fromPage: number, reviewPage = 1): string {
  const query = new URLSearchParams();
  if (fromPage > 1) {
    query.set("from", String(fromPage));
  }
  if (reviewPage > 1) {
    query.set("reviewPage", String(reviewPage));
  }
  const text = query.toString();
  return text ? `/works/${id}?${text}` : `/works/${id}`;
}
