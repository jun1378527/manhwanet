import type { Credit } from "@/lib/api/types";
import { groupCredits } from "@/lib/works/labels";

type Props = {
  credits: Credit[];
  className?: string;
};

/** 역할별 작가 크레딧. 예) 글 한서윤 · 그림 한서윤 · 원작 윤도현 */
export function CreditList({ credits, className }: Props) {
  const groups = groupCredits(credits);
  if (groups.length === 0) {
    return <p className={className}>작가 정보 없음</p>;
  }
  return (
    <dl className={["credits", className].filter(Boolean).join(" ")}>
      {groups.map((group) => (
        <div key={group.role} className="credits__item">
          <dt>{group.label}</dt>
          <dd>{group.names.join(", ")}</dd>
        </div>
      ))}
    </dl>
  );
}
