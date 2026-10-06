"use client";

import { useEffect } from "react";

/** 예상하지 못한 오류(버그 등)용 경계. API 연결 실패는 페이지에서 직접 안내한다. */
export default function WorksError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <section className="status" role="alert">
      <h2 className="status__title">화면을 표시하는 중 문제가 발생했습니다</h2>
      <p>잠시 후 다시 시도해 주세요.</p>
      <button type="button" className="button" onClick={() => retry()}>
        다시 시도
      </button>
    </section>
  );
}
