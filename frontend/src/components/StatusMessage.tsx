import type { ReactNode } from "react";

type Props = {
  title: string;
  children?: ReactNode;
  /** alert: 오류 안내(스크린 리더가 즉시 읽음), status: 일반 안내 */
  role?: "alert" | "status";
};

/** 빈 목록, 연결 실패, 없는 작품 등 상태 안내 블록 */
export function StatusMessage({ title, children, role = "status" }: Props) {
  return (
    <section className="status" role={role}>
      <h2 className="status__title">{title}</h2>
      {children}
    </section>
  );
}

/** 백엔드 API에 연결하지 못했을 때의 안내. retryHref 로 같은 화면을 다시 요청한다. */
export function ApiUnavailable({ retryHref }: { retryHref: string }) {
  return (
    <StatusMessage title="작품 정보를 불러오지 못했습니다" role="alert">
      <p>서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
      {/* 전체 페이지를 다시 요청하도록 일반 링크를 사용한다. */}
      <a className="button" href={retryHref}>
        다시 시도
      </a>
    </StatusMessage>
  );
}
