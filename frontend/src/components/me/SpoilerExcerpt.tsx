"use client";

import { useId, useState } from "react";

/** 스포일러 리뷰 본문 일부. 기본으로 접고, 누르면 펼친다(펼친 상태는 기억하지 않음) */
export function SpoilerExcerpt({ excerpt, truncated }: { excerpt: string; truncated: boolean }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <div className="me-review__spoiler">
      <div id={id}>
        {open ? (
          <p className="me-review__excerpt" dir="auto">
            {excerpt}
            {truncated ? "…" : null}
          </p>
        ) : (
          <p className="me-review__hidden">스포일러가 포함된 리뷰입니다.</p>
        )}
      </div>
      <button type="button" className="link-button" aria-expanded={open} aria-controls={id} onClick={() => setOpen((v) => !v)}>
        {open ? "스포일러 내용 숨기기" : "스포일러 내용 보기"}
      </button>
    </div>
  );
}
