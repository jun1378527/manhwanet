import type { ReactNode } from "react";

import type { PrimaryTitle, WorkFormat } from "@/lib/api/types";
import { demoCoverFor, type DemoCoverId } from "@/lib/home/demo-covers";
import { WORK_FORMAT_LABELS } from "@/lib/works/labels";

/**
 * 작품 카드 표지 자리. 장식이며(aria-hidden) 제목·작가는 카드의 텍스트가 맡는다.
 * - 가상 데모 작품: 시안에서 직접 만든 데모 표지(“가상 표지” 표시)
 * - 그 밖의 작품: 표지 없음 디자인(제목 글자나 임의 그림을 쓰지 않음)
 */
export function WorkCover({ title, format }: { title: PrimaryTitle; format: WorkFormat }) {
  const demo = demoCoverFor(title);
  return (
    <span className="cover" aria-hidden="true">
      {demo ? <DemoCover id={demo} /> : <NoCover format={format} />}
    </span>
  );
}

function NoCover({ format }: { format: WorkFormat }) {
  return (
    <span className={`cover__empty cover__empty--${format === "WEBTOON" ? "webtoon" : "page"}`}>
      <svg className="cover__empty-icon" viewBox="0 0 32 32" width="30" height="30" focusable="false">
        <rect x="7" y="4" width="18" height="24" rx="2.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
        <path d="M11 10h10M11 14.5h7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        <path d="M11 22l3.5-3.5 2.5 2.5 2-2 2 3" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
      </svg>
      <span className="cover__empty-label">표지 없음</span>
      <span className="cover__empty-format">{WORK_FORMAT_LABELS[format] ?? format}</span>
    </span>
  );
}

const DEMO_ART: Record<DemoCoverId, { top: string; bottom: string; art: ReactNode }> = {
  "star-garden": {
    top: "#1d2b64",
    bottom: "#5b6ee1",
    art: (
      <>
        <circle cx="212" cy="104" r="54" fill="#ffe9a8" />
        <circle cx="236" cy="92" r="48" fill="#1d2b64" opacity="0.85" />
        <g fill="#ffffff" opacity="0.7">
          <circle cx="60" cy="70" r="2.4" />
          <circle cx="112" cy="134" r="2.8" />
          <circle cx="254" cy="206" r="2" />
          <circle cx="46" cy="186" r="2" />
          <circle cx="160" cy="58" r="1.8" />
        </g>
        <path d="M0 300 Q70 252 150 286 T300 268 V400 H0Z" fill="#0f1a44" opacity="0.55" />
        <g fill="#9be3b8">
          <path d="M70 300c-6-28 8-46 22-52-2 18-8 34-22 52z" />
          <path d="M96 304c4-30 22-42 38-44-6 18-18 32-38 44z" />
          <path d="M210 300c-4-26 10-40 24-44-4 18-10 30-24 44z" />
        </g>
      </>
    ),
  },
  "moon-bookshop": {
    top: "#3b2416",
    bottom: "#c9874f",
    art: (
      <>
        <circle cx="226" cy="76" r="22" fill="#ffd27a" />
        <path d="M58 132h82q10 0 10 10v146q-10-10-20-10H58z" fill="#ffffff" opacity="0.88" />
        <path d="M242 132h-82q-10 0-10 10v146q10-10 20-10h72z" fill="#ffffff" opacity="0.72" />
        <path d="M72 164h56M72 184h46M72 204h52M172 164h56M172 184h44" stroke="#3b2416" strokeWidth="4" opacity="0.35" />
      </>
    ),
  },
  "clock-tower": {
    top: "#2a0f12",
    bottom: "#c43a3a",
    art: (
      <>
        <path d="M126 316l10-170h28l10 170z" fill="#ffffff" opacity="0.92" />
        <path d="M120 150l30-44 30 44z" fill="#ffd27a" />
        <circle cx="150" cy="180" r="18" fill="#2a0f12" />
        <path d="M150 168v12l8 6" stroke="#ffd27a" strokeWidth="3" strokeLinecap="round" fill="none" />
        <g fill="#ffd27a" opacity="0.6">
          <circle cx="62" cy="80" r="2.2" />
          <circle cx="240" cy="120" r="2.6" />
          <circle cx="90" cy="150" r="1.8" />
        </g>
        <path d="M0 316 Q75 300 150 316 T300 316 V400 H0Z" fill="#000000" opacity="0.32" />
      </>
    ),
  },
};

function DemoCover({ id }: { id: DemoCoverId }) {
  const { top, bottom, art } = DEMO_ART[id];
  const gradientId = `demo-cover-${id}`;
  return (
    <svg className="cover__demo" viewBox="0 0 300 400" preserveAspectRatio="xMidYMid slice" focusable="false">
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={top} />
          <stop offset="1" stopColor={bottom} />
        </linearGradient>
      </defs>
      <rect width="300" height="400" fill={`url(#${gradientId})`} />
      {art}
      <rect x="198" y="16" width="86" height="26" rx="13" fill="rgba(255,255,255,.9)" />
      <text x="241" y="34" textAnchor="middle" fontSize="13" fontWeight="700" fill="#15171c">
        가상 표지
      </text>
    </svg>
  );
}
