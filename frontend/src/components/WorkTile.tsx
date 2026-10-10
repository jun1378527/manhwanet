import type { PrimaryTitle, WorkFormat } from "@/lib/api/types";
import { WORK_FORMAT_LABELS } from "@/lib/works/labels";
import { titleInitial } from "@/lib/works/tile";

type Props = {
  title: PrimaryTitle;
  format: WorkFormat;
  size?: "md" | "lg";
};

/**
 * 표지 데이터가 없는 현재 상태에서 쓰는 제목 기반 타일. 실제 표지가 아니며 장식이다(aria-hidden).
 * 대표 제목의 첫 글자와 작품 형식(색·글자)만 쓴다. 스크린 리더는 옆의 제목·형식 텍스트를 읽는다.
 */
export function WorkTile({ title, format, size = "md" }: Props) {
  const formatClass = format === "WEBTOON" ? "work-tile--webtoon" : "work-tile--page";
  return (
    <span className={`work-tile ${formatClass} work-tile--${size}`} aria-hidden="true">
      <span className="work-tile__glyph" lang={title.languageCode}>
        {titleInitial(title.title)}
      </span>
      <span className="work-tile__format">{WORK_FORMAT_LABELS[format] ?? format}</span>
    </span>
  );
}
