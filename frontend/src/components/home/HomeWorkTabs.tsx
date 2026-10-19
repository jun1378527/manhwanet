"use client";

import Link from "next/link";
import { useRef, useState, type KeyboardEvent } from "react";

import type { WorkSummary } from "@/lib/api/types";
import type { HomeWorkTab } from "@/lib/home/model";

import { HomeWorkCard } from "./HomeWorkCard";

const HOME_HREF = "/";

export type HomeWorkPanel = {
  id: HomeWorkTab;
  label: string;
  /** 정렬 기준 설명(전체 순위인지, 등록 시각 기준인지) */
  description: string;
  state:
    | { status: "ok"; works: WorkSummary[]; total: number }
    | { status: "error" };
  emptyTitle: string;
  emptyText: string;
  /** 순위 번호를 붙일지(전체 순위일 때만) */
  ranked: boolean;
};

/**
 * 홈 작품 영역의 정렬 탭(버튼 묶음 형태). 세 목록은 서버가 미리 불러온다.
 * 평점순·평가 많은 순은 백엔드가 전체 공개 작품에서 정한 순위이고, 최근 등록은 사이트 등록 시각 기준이다.
 */
export function HomeWorkTabs({ panels }: { panels: HomeWorkPanel[] }) {
  const [active, setActive] = useState(0);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const panel = panels[active];

  const select = (index: number, focus: boolean) => {
    const next = (index + panels.length) % panels.length;
    setActive(next);
    if (focus) tabRefs.current[next]?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const keys: Record<string, number> = { ArrowRight: active + 1, ArrowLeft: active - 1, Home: 0, End: panels.length - 1 };
    if (event.key in keys) {
      event.preventDefault();
      select(keys[event.key], true);
    }
  };

  return (
    <div className="home-works__tabs-wrap">
      <div className="segmented" role="tablist" aria-label="작품 정렬">
        {panels.map((p, i) => (
          <button
            key={p.id}
            ref={(el) => {
              tabRefs.current[i] = el;
            }}
            type="button"
            role="tab"
            id={`home-works-tab-${p.id}`}
            aria-selected={i === active}
            aria-controls={`home-works-panel-${p.id}`}
            tabIndex={i === active ? 0 : -1}
            className="segmented__button"
            onClick={() => select(i, false)}
            onKeyDown={onKeyDown}
          >
            {p.label}
          </button>
        ))}
      </div>
      {panels.map((p, i) => (
        <div
          key={p.id}
          id={`home-works-panel-${p.id}`}
          role="tabpanel"
          aria-labelledby={`home-works-tab-${p.id}`}
          hidden={i !== active}
          className="home-works__panel"
        >
          {i === active ? <PanelBody panel={panel} /> : null}
        </div>
      ))}
    </div>
  );
}

function PanelBody({ panel }: { panel: HomeWorkPanel }) {
  if (panel.state.status === "error") {
    return (
      <div className="home-state home-state--error" role="alert">
        <p className="home-state__title">작품을 불러오지 못했습니다</p>
        <p>서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
        {/* 전체 페이지를 다시 요청하도록 일반 링크를 쓴다(ApiUnavailable 과 같은 방식) */}
        <a className="button button--secondary" href={HOME_HREF}>
          다시 시도
        </a>
      </div>
    );
  }
  const { works, total } = panel.state;
  return (
    <>
      <p className="home-works__desc">
        {panel.description}
        {panel.ranked && total > 0 ? ` · 대상 ${total.toLocaleString("ko-KR")}개` : null}
      </p>
      {works.length === 0 ? (
        <div className="home-state">
          <p className="home-state__title">{panel.emptyTitle}</p>
          <p>{panel.emptyText}</p>
          <Link className="button button--secondary" href="/works">
            전체 작품 보기
          </Link>
        </div>
      ) : (
        <ol className="home-grid">
          {works.map((work, i) => (
            <li key={work.id}>
              <HomeWorkCard work={work} rank={panel.ranked ? i + 1 : undefined} showRegistered={!panel.ranked} />
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
