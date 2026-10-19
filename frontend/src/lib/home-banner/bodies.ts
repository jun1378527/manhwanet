/**
 * 홈 배너 물리 장면에 떨어뜨릴 물체 계획과, 물리 엔진 없이 보여 줄 정적 배치.
 * 물리 엔진과 캔버스에 의존하지 않는 순수 계산이라 테스트할 수 있다.
 *
 * 표지는 디자인 시안(docs/ui-mockups/home-portal-v2)에서 직접 만든 가상 표지 색 조합만 쓴다.
 * 실제 작품으로 오해하지 않도록 제목 글자는 넣지 않고 색과 무늬로만 그린다.
 */
import type { BannerSlideId } from "./slides";

/** 시안용 가상 표지 색: [위, 아래, 무늬] */
export const COVER_PALETTES: readonly (readonly [string, string, string])[] = [
  ["#1d2b64", "#5b6ee1", "#ffe9a8"],
  ["#111318", "#4a4f5c", "#e5483f"],
  ["#f6d5a8", "#e08a4a", "#3a1d08"],
  ["#0f3d3e", "#3fa58a", "#9be3b8"],
  ["#f2efe6", "#9aa59a", "#e5483f"],
  ["#ffe7e0", "#f4a38f", "#5a1f12"],
  ["#ff7a3d", "#ffc94d", "#2b1200"],
  ["#2c1a4d", "#c86fc9", "#ffe9f6"],
  ["#d9f0ff", "#7cc4f2", "#0b2a40"],
  ["#3b2416", "#c9874f", "#ffd27a"],
  ["#eef4fb", "#a9c4e8", "#e5483f"],
  ["#2a0f12", "#c43a3a", "#ffd27a"],
  ["#fff4dc", "#e9b44c", "#3d2a00"],
  ["#0b1026", "#3d4dbb", "#ffd27a"],
];

export const STAR_FILL = "#ffd166";
export const STAR_STROKE = "#e0a92e";
export const CHECK_FILL = "#7fdcb0";

export type BodyPlan =
  | { kind: "cover"; x: number; y: number; w: number; h: number; angle: number; palette: number; delayMs: number }
  | { kind: "star"; x: number; y: number; r: number; angle: number; delayMs: number }
  | { kind: "chip"; x: number; y: number; w: number; h: number; label: string; highlight: boolean; delayMs: number }
  | { kind: "block"; x: number; y: number; w: number; h: number; label: string; step: number; color: string; delayMs: number }
  | { kind: "check"; x: number; y: number; r: number; delayMs: number };

export const RATE_CHIPS: readonly string[] = ["★ 4.5", "★ 5.0", "★ 3.5", "한 줄 리뷰"];
export const ABOUT_BLOCKS: readonly { label: string; color: string }[] = [
  { label: "작품 찾기", color: "#e6e8ec" },
  { label: "별점 남기기", color: "#bfe9d3" },
  { label: "리뷰 쓰기", color: "#7fdcb0" },
];

/** 장면 크기에 맞춘 물체 크기 배율 */
export function sceneScale(width: number, height: number): number {
  return Math.max(0.72, Math.min(1.2, Math.min(width / 620, height / 330)));
}

/** 좁은 화면에서는 물체 수를 줄인다 */
export function isNarrow(width: number): boolean {
  return width < 420;
}

/**
 * 슬라이드별 물체 계획. 모든 물체는 장면 위(y < 0)에서 시작하고 가로로는 장면 안에 있다.
 * @param random 0 이상 1 미만을 돌려주는 난수 함수(테스트에서는 고정 난수)
 */
export function planBodies(slide: BannerSlideId, width: number, height: number, random: () => number): BodyPlan[] {
  const k = sceneScale(width, height);
  const narrow = isNarrow(width);
  const between = (a: number, b: number) => a + random() * (b - a);
  const insideX = (half: number) => between(half, Math.max(half, width - half));
  const plans: BodyPlan[] = [];

  if (slide === "explore") {
    const count = narrow ? 10 : 18;
    for (let i = 0; i < count; i++) {
      const s = between(0.88, 1.12) * k;
      const w = 60 * s;
      const h = 80 * s;
      plans.push({
        kind: "cover", x: insideX(w), y: -between(40, 520), w, h, angle: between(-0.6, 0.6),
        palette: i % COVER_PALETTES.length, delayMs: i * 55,
      });
    }
  } else if (slide === "rate") {
    const count = narrow ? 12 : 18;
    for (let i = 0; i < count; i++) {
      const r = between(14, 25) * k;
      plans.push({ kind: "star", x: insideX(r * 2), y: -between(40, 560), r, angle: between(0, Math.PI * 2), delayMs: i * 55 });
    }
    RATE_CHIPS.forEach((label, i) => {
      const w = (label.length * 9 + 30) * k;
      const h = 30 * k;
      plans.push({
        kind: "chip", x: insideX(w), y: -between(80, 520), w, h, label,
        highlight: i === RATE_CHIPS.length - 1, delayMs: (count + i) * 55,
      });
    });
  } else {
    ABOUT_BLOCKS.forEach(({ label, color }, i) => {
      const w = (label.length * 15 + 58) * k;
      const h = 42 * k;
      const x = Math.min(Math.max(width * 0.56 + between(-14, 14) * k, w / 2), width - w / 2);
      plans.push({ kind: "block", x, y: -60 - i * 120, w, h, label, step: i + 1, color, delayMs: i * 380 });
    });
    const count = narrow ? 7 : 11;
    for (let i = 0; i < count; i++) {
      const r = between(11, 17) * k;
      plans.push({ kind: "check", x: insideX(r * 2), y: -between(300, 800), r, delayMs: 1200 + i * 60 });
    }
  }
  return plans;
}

/** 고정 시드 난수(mulberry32). 같은 시드면 같은 배치를 만든다 */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * 물리 엔진 없이 보여 줄 정적 배치(움직임 줄이기·로딩 중·로딩 실패).
 * 좌표계는 600×340, 바닥은 y=340 이다.
 */
export type StaticItem =
  | { kind: "cover"; x: number; y: number; w: number; h: number; angle: number; palette: number }
  | { kind: "star"; x: number; y: number; r: number; angle: number }
  | { kind: "chip"; x: number; y: number; w: number; h: number; label: string; highlight: boolean }
  | { kind: "block"; x: number; y: number; w: number; h: number; label: string; step: number; color: string }
  | { kind: "check"; x: number; y: number; r: number };

export const STATIC_VIEWBOX = { width: 600, height: 340 } as const;

const COVER_W = 60;
const COVER_H = 80;

export function staticLayout(slide: BannerSlideId): StaticItem[] {
  if (slide === "explore") {
    const base = [40, 104, 168, 232, 296, 360, 424, 488, 552];
    const row1 = base.map((x, i) => ({ x, y: 300, angle: [0.02, -0.04, 0.03, 0, -0.03, 0.05, -0.02, 0.04, -0.05][i] }));
    const row2 = [{ x: 82, a: -0.18 }, { x: 160, a: 0.12 }, { x: 250, a: -0.08 }, { x: 345, a: 0.2 }, { x: 440, a: -0.14 }, { x: 520, a: 0.1 }]
      .map(({ x, a }) => ({ x, y: 222, angle: a }));
    const row3 = [{ x: 205, a: 0.3 }, { x: 300, a: -0.22 }, { x: 395, a: 0.16 }].map(({ x, a }) => ({ x, y: 150, angle: a }));
    return [...row1, ...row2, ...row3].map((p, i) => ({
      kind: "cover" as const, ...p, w: COVER_W, h: COVER_H, palette: i % COVER_PALETTES.length,
    }));
  }
  if (slide === "rate") {
    const stars: StaticItem[] = [
      [30, 318, 20], [75, 322, 16], [118, 314, 24], [168, 320, 18], [212, 312, 25], [262, 321, 17], [305, 316, 22],
      [352, 320, 18], [398, 313, 24], [446, 321, 17], [490, 315, 22], [538, 320, 18], [578, 317, 20], [145, 278, 18], [420, 274, 20],
    ].map(([x, y, r], i) => ({ kind: "star" as const, x, y, r, angle: i * 0.7 }));
    const chips: StaticItem[] = RATE_CHIPS.map((label, i) => ({
      kind: "chip" as const, label, highlight: i === RATE_CHIPS.length - 1, h: 30, w: label.length * 9 + 30,
      x: [96, 262, 330, 490][i], y: [272, 280, 250, 276][i],
    }));
    return [...stars, ...chips];
  }
  const blocks: StaticItem[] = ABOUT_BLOCKS.map(({ label, color }, i) => ({
    kind: "block" as const, label, color, step: i + 1, w: label.length * 15 + 58, h: 42, x: 340 + [0, 14, -8][i], y: 317 - i * 44,
  }));
  const checks: StaticItem[] = [40, 82, 124, 170, 214, 470, 512, 556].map((x, i) => ({ kind: "check" as const, x, y: 324, r: i % 2 ? 14 : 16 }));
  return [...blocks, ...checks, { kind: "check", x: 352, y: 168, r: 15 }];
}
