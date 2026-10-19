"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, type ReactNode, useId, useOptimistic, useRef, useState, useTransition } from "react";

import type { WorkFormat } from "@/lib/api/types";
import { WORK_FORMAT_LABELS } from "@/lib/works/labels";
import {
  MAX_QUERY_LENGTH,
  WORK_FORMATS,
  WORK_SORTS,
  WORK_SORT_LABELS,
  WORK_SORT_NOTES,
  type WorkSort,
  type WorksQuery,
  WORKS_PATH,
  activeFilterCount,
  changeWorksQuery,
  isWorkFormat,
  normalizeQuery,
  worksSearchHref,
} from "@/lib/works/search";

export type PlatformOption = { code: string; name: string };

type Props = {
  /** URL 에서 해석한(서버가 결과를 만든) 조건 */
  query: WorksQuery;
  /** 플랫폼 선택지. 불러오지 못했으면 null */
  platforms: PlatformOption[] | null;
  /** 서버가 만든 결과 영역(건수·카드·페이지 이동) */
  children: ReactNode;
};

/**
 * 작품 찾기 조작부: 검색창, 정렬, 필터(형식·플랫폼), 초기화.
 * - 상태는 URL 에만 둔다. 조건을 바꾸면 첫 페이지 주소로 이동(router.push)하고, 서버가 그 주소로 결과를 만든다.
 *   새로고침·뒤로가기는 URL 로 복원된다.
 * - 이동은 transition 으로 처리해 새 결과가 준비될 때까지 이전 결과를 흐리게 보여 준다. 연달아 바꾸면 마지막 이동만 반영된다
 *   (앞선 이동의 늦은 응답은 버려진다).
 * - JS 가 없으면 같은 form 이 GET /works 로 제출되고, 서버가 정규 주소로 보낸다.
 * - 좁은 화면에서는 검색·정렬을 바로 보여 주고 필터는 접어 둔다.
 */
export function WorksExplorer({ query, platforms, children }: Props) {
  const router = useRouter();
  const [isPending, startTransition] = useTransition();
  const [shown, setShown] = useOptimistic(query);
  const [draft, setDraft] = useState(query.q);
  const [syncedQ, setSyncedQ] = useState(query.q);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const ids = useId();

  // 뒤로가기·링크 이동 등으로 URL 의 검색어가 바뀌면 입력창도 맞춘다(렌더 중 상태 조정)
  if (query.q !== syncedQ) {
    setSyncedQ(query.q);
    setDraft(query.q);
  }

  const navigate = (next: WorksQuery) => {
    startTransition(() => {
      setShown(next);
      router.push(worksSearchHref(next), { scroll: false });
    });
  };

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const q = normalizeQuery(draft);
    setDraft(q);
    navigate(changeWorksQuery(shown, { q }));
  };

  const clearSearch = () => {
    setDraft("");
    inputRef.current?.focus();
    if (shown.q !== "") {
      navigate(changeWorksQuery(shown, { q: "" }));
    }
  };

  const filterCount = activeFilterCount(shown);
  const panelId = `${ids}-filters`;
  const noteId = `${ids}-sort-note`;
  const platformMissing = platforms === null;
  // 선택지를 불러오지 못해도 URL 에 있는 플랫폼 조건은 결과에 걸려 있으므로 그 값을 선택 상자에 보여 준다
  const platformOptions = platforms ?? (shown.platform ? [{ code: shown.platform, name: shown.platform }] : []);

  return (
    <div className="works-explorer">
      <form
        className="works-controls"
        action={WORKS_PATH}
        method="get"
        role="search"
        aria-label="작품 찾기"
        onSubmit={onSubmit}
      >
        <div className="works-search">
          <label htmlFor={`${ids}-q`} className="visually-hidden">
            작품명·작가명 검색
          </label>
          <svg className="works-search__icon" width="18" height="18" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="9" cy="9" r="6" fill="none" stroke="currentColor" strokeWidth="2" />
            <path d="m14 14 4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <input
            ref={inputRef}
            id={`${ids}-q`}
            className="works-search__input"
            type="search"
            name="q"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            maxLength={MAX_QUERY_LENGTH}
            placeholder="작품명이나 작가명"
            autoComplete="off"
            enterKeyHint="search"
            spellCheck={false}
          />
          {draft !== "" ? (
            <button type="button" className="works-search__clear" onClick={clearSearch} aria-label="검색어 지우기">
              <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
                <path d="m3 3 8 8M11 3l-8 8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
            </button>
          ) : null}
          {/* JS 없이 제출해도 지금 정렬을 유지한다 */}
          <input type="hidden" name="sort" value={shown.sort} />
          <button type="submit" className="button works-search__submit">
            검색
          </button>
        </div>

        <div className="works-bar">
          <nav className="works-sort" aria-label="정렬" aria-describedby={noteId}>
            <ul className="works-sort__list">
              {WORK_SORTS.map((sort) => {
                const active = shown.sort === sort;
                const href = worksSearchHref(changeWorksQuery(shown, { sort }));
                return (
                  <li key={sort}>
                    <Link
                      href={href}
                      scroll={false}
                      className="works-sort__link"
                      aria-current={active ? "true" : undefined}
                      onClick={(event) => {
                        // 새 탭 열기 등 보조 키 클릭은 브라우저에 맡긴다
                        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
                        event.preventDefault();
                        if (!active) navigate(changeWorksQuery(shown, { sort }));
                      }}
                    >
                      <SortIcon sort={sort} />
                      {WORK_SORT_LABELS[sort]}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>

          <button
            type="button"
            className="works-bar__toggle"
            aria-expanded={filtersOpen}
            aria-controls={panelId}
            onClick={() => setFiltersOpen((open) => !open)}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M2 4h12M4 8h8M6 12h4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
            필터
            {filterCount > 0 ? (
              <span className="works-bar__count">
                <span className="visually-hidden">적용 </span>
                {filterCount}
                <span className="visually-hidden">개</span>
              </span>
            ) : null}
          </button>
        </div>

        <div id={panelId} className="works-filters" data-open={filtersOpen}>
          <fieldset className="works-filter">
            <legend className="works-filter__legend">작품 형식</legend>
            <div className="works-chips">
              {([null, ...WORK_FORMATS] as (WorkFormat | null)[]).map((format) => (
                <label key={format ?? "all"} className="works-chip">
                  <input
                    type="radio"
                    name="format"
                    value={format ?? ""}
                    checked={shown.format === format}
                    onChange={(event) => {
                      const value = event.target.value;
                      navigate(changeWorksQuery(shown, { format: isWorkFormat(value) ? value : null }));
                    }}
                  />
                  <span>{format ? WORK_FORMAT_LABELS[format] ?? format : "전체"}</span>
                </label>
              ))}
            </div>
          </fieldset>

          <div className="works-filter">
            <label htmlFor={`${ids}-platform`} className="works-filter__legend">
              플랫폼
            </label>
            <select
              id={`${ids}-platform`}
              name="platform"
              className="works-select"
              value={shown.platform ?? ""}
              disabled={platformMissing}
              aria-describedby={platformMissing ? `${ids}-platform-error` : undefined}
              onChange={(event) => navigate(changeWorksQuery(shown, { platform: event.target.value || null }))}
            >
              <option value="">전체 플랫폼</option>
              {platformOptions.map((platform) => (
                <option key={platform.code} value={platform.code}>
                  {platform.name}
                </option>
              ))}
            </select>
            {platformMissing ? (
              <p id={`${ids}-platform-error`} className="works-filter__error">
                플랫폼 목록을 불러오지 못했어요.
              </p>
            ) : null}
          </div>

          <button
            type="button"
            className="works-reset"
            disabled={filterCount === 0}
            onClick={() => navigate(changeWorksQuery(shown, { format: null, platform: null }))}
          >
            필터 초기화
          </button>
        </div>
        {/* JS 가 꺼져 있으면 접기 단추가 동작하지 않으므로 필터를 펼쳐 둔다 */}
        <noscript>
          <style>{`.works-filters{display:flex!important}.works-bar__toggle{display:none!important}`}</style>
        </noscript>
      </form>

      <p id={noteId} className="works-note">
        {WORK_SORT_NOTES[shown.sort]}
      </p>

      <div className="works-results" aria-busy={isPending} data-pending={isPending}>
        {isPending ? (
          <p className="works-results__pending" role="status">
            결과를 불러오는 중…
          </p>
        ) : null}
        {children}
      </div>
    </div>
  );
}

/** 정렬 앞 작은 아이콘(장식) */
function SortIcon({ sort }: { sort: WorkSort }) {
  const common = { width: 14, height: 14, viewBox: "0 0 16 16", "aria-hidden": true, className: "works-sort__icon" } as const;
  const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.6, strokeLinecap: "round", strokeLinejoin: "round" } as const;
  switch (sort) {
    case "popular":
      return (
        <svg {...common}>
          <path d="M8 1.8c.4 2.4 3.9 4 3.9 7.6A3.9 3.9 0 0 1 8 13.3a3.9 3.9 0 0 1-3.9-3.9c0-1.8 1-2.9 1.9-3.7.1 1.2.7 2 1.5 2.3C7.3 6 7.2 3.8 8 1.8Z" {...stroke} />
        </svg>
      );
    case "ratingCount":
      return (
        <svg {...common}>
          <path d="m5.6 2.2.8 1.7 1.8.3-1.3 1.3.3 1.8-1.6-.9-1.6.9.3-1.8L3 4.2l1.8-.3.8-1.7ZM10.6 7.2l.8 1.7 1.8.3-1.3 1.3.3 1.8-1.6-.9-1.6.9.3-1.8L8 9.2l1.8-.3.8-1.7Z" {...stroke} />
        </svg>
      );
    case "reviewCount":
      return (
        <svg {...common}>
          <path d="M2.5 3.5h8v5.5h-4l-2.5 2V9h-1.5zM10.5 6h3v5.5H12V13l-2-1.5H7" {...stroke} />
        </svg>
      );
    case "rating":
      return (
        <svg {...common}>
          <path d="m8 1.8 1.9 3.9 4.3.6-3.1 3 .7 4.3L8 11.6l-3.8 2 .7-4.3-3.1-3 4.3-.6L8 1.8Z" {...stroke} />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <circle cx="8" cy="8" r="5.8" {...stroke} />
          <path d="M8 4.8V8l2.2 1.4" {...stroke} />
        </svg>
      );
  }
}
