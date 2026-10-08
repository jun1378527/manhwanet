/**
 * 리뷰 시각 표시. API 는 UTC ISO-8601 이고, 화면은 서버·브라우저 시간대와 관계없이 Asia/Seoul 로 표시한다.
 * 서버 렌더링과 브라우저 렌더링 결과가 같도록 formatToParts 의 숫자만 모아 직접 조립한다.
 */
const FORMATTER = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** "2026년 10월 8일 16:58" (Asia/Seoul) */
export function formatSeoulDateTime(iso: string): string {
  const parts: Record<string, string> = {};
  for (const part of FORMATTER.formatToParts(new Date(iso))) {
    parts[part.type] = part.value;
  }
  return `${parts.year}년 ${parts.month}월 ${parts.day}일 ${parts.hour}:${parts.minute}`;
}
