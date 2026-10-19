import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { ABOUT_BLOCKS, COVER_PALETTES, planBodies, seededRandom, staticLayout, STATIC_VIEWBOX, type BodyPlan } from "./bodies";
import { AUTOPLAY_MS, MAX_FRAME_MS, isAutoplayPaused, stepAutoplay, wrapIndex, type PauseReasons } from "./carousel";
import { BANNER_SLIDES } from "./slides";

const running: PauseReasons = { user: false, hover: false, focus: false, reducedMotion: false, hidden: false };

describe("배너 슬라이드 내용", () => {
  it("슬라이드 3개: 작품 탐색, 별점·리뷰, 서비스 소개", () => {
    assert.deepEqual(BANNER_SLIDES.map((s) => s.id), ["explore", "rate", "about"]);
  });

  it("탭의 보이는 이름은 접근 가능한 이름(kicker)에 들어 있다", () => {
    for (const slide of BANNER_SLIDES) {
      assert.ok(slide.kicker.includes(slide.tabLabel), slide.tabLabel);
    }
  });

  it("링크는 지금 있는 화면으로만 간다", () => {
    for (const slide of BANNER_SLIDES) {
      assert.ok(["/works", "/signup"].includes(slide.cta.href), slide.cta.href);
      assert.ok(slide.cta.label.length > 0);
    }
    assert.equal(BANNER_SLIDES[0].cta.href, "/works");
  });

  it("이용자용 문구에 내부 처리 용어가 없다", () => {
    const text = BANNER_SLIDES.flatMap((s) => [s.kicker, ...s.titleLines, s.description, ...(s.points ?? [])]).join(" ");
    for (const word of ["검수", "DB", "데이터베이스", "staging", "수집", "숨김", "HIDDEN"]) {
      assert.ok(!text.includes(word), word);
    }
  });
});

describe("자동 넘김", () => {
  it("인덱스를 앞뒤로 순환한다", () => {
    assert.equal(wrapIndex(3, 3), 0);
    assert.equal(wrapIndex(-1, 3), 2);
    assert.equal(wrapIndex(4, 3), 1);
    assert.equal(wrapIndex(1, 0), 0);
  });

  it("8초가 지나면 넘어가고 시간은 0으로 돌아간다", () => {
    let elapsed = 0;
    let advanced = 0;
    for (let t = 0; t < AUTOPLAY_MS + 50; t += 16) {
      const step = stepAutoplay(elapsed, 16, running);
      elapsed = step.elapsed;
      if (step.advance) {
        advanced++;
        assert.equal(step.elapsed, 0);
      }
    }
    assert.equal(advanced, 1);
    assert.equal(AUTOPLAY_MS, 8000);
  });

  it("멈춘 이유가 하나라도 있으면 시간이 흐르지 않는다", () => {
    for (const key of Object.keys(running) as (keyof PauseReasons)[]) {
      const reasons = { ...running, [key]: true };
      assert.equal(isAutoplayPaused(reasons), true, key);
      const step = stepAutoplay(3000, 500, reasons);
      assert.equal(step.elapsed, 3000, key);
      assert.equal(step.advance, false, key);
    }
    assert.equal(isAutoplayPaused(running), false);
  });

  it("재개하면 남은 시간부터 이어 가고, 큰 프레임 간격은 상한으로 자른다", () => {
    const resumed = stepAutoplay(7000, 16, running);
    assert.equal(resumed.elapsed, 7016);
    const jump = stepAutoplay(0, 60_000, running);
    assert.equal(jump.elapsed, MAX_FRAME_MS);
    assert.equal(jump.advance, false);
    assert.equal(stepAutoplay(100, Number.NaN, running).elapsed, 100);
  });
});

describe("물체 계획", () => {
  const within = (p: BodyPlan, width: number) => {
    const half = "w" in p ? p.w / 2 : p.r;
    return p.x - half >= -0.001 && p.x + half <= width + 0.001;
  };

  for (const [width, height] of [[700, 384], [350, 210]]) {
    it(`${width}×${height}: 모든 물체가 장면 위에서 시작하고 가로로 장면 안에 있다`, () => {
      for (const slide of ["explore", "rate", "about"] as const) {
        const plans = planBodies(slide, width, height, seededRandom(7));
        assert.ok(plans.length > 0);
        for (const p of plans) {
          assert.ok(p.y < 0, `${slide} ${p.kind} y=${p.y}`);
          assert.ok(within(p, width), `${slide} ${p.kind} x=${p.x}`);
          assert.ok(p.delayMs >= 0);
        }
      }
    });
  }

  it("좁은 화면에서는 물체 수를 줄인다", () => {
    const wide = planBodies("explore", 700, 384, seededRandom(1)).length;
    const narrow = planBodies("explore", 350, 210, seededRandom(1)).length;
    assert.ok(narrow < wide);
  });

  it("같은 시드는 같은 배치", () => {
    assert.deepEqual(planBodies("rate", 600, 340, seededRandom(3)), planBodies("rate", 600, 340, seededRandom(3)));
  });

  it("소개 슬라이드 블록은 차례로 떨어진다", () => {
    const blocks = planBodies("about", 700, 384, seededRandom(2)).filter((p) => p.kind === "block");
    assert.equal(blocks.length, ABOUT_BLOCKS.length);
    for (let i = 1; i < blocks.length; i++) {
      assert.ok(blocks[i].delayMs > blocks[i - 1].delayMs);
      assert.ok(blocks[i].y < blocks[i - 1].y);
    }
  });

  it("표지는 시안 색 조합만 쓴다", () => {
    const covers = planBodies("explore", 700, 384, seededRandom(4)).filter((p) => p.kind === "cover");
    for (const c of covers) {
      if (c.kind === "cover") assert.ok(c.palette >= 0 && c.palette < COVER_PALETTES.length);
    }
  });
});

describe("정적 배치", () => {
  it("모든 항목이 600×340 안에 있다", () => {
    for (const slide of ["explore", "rate", "about"] as const) {
      const items = staticLayout(slide);
      assert.ok(items.length > 0);
      for (const item of items) {
        // 회전하는 표지는 긴 변 기준, 블록·칩은 실제 너비·높이 기준
        const halfX = "w" in item ? (item.kind === "cover" ? Math.max(item.w, item.h) : item.w) / 2 : item.r;
        const halfY = "w" in item ? (item.kind === "cover" ? Math.max(item.w, item.h) : item.h) / 2 : item.r;
        assert.ok(item.x - halfX >= -10 && item.x + halfX <= STATIC_VIEWBOX.width + 10, `${slide} ${item.kind} x`);
        assert.ok(item.y - halfY >= 0 && item.y + halfY <= STATIC_VIEWBOX.height + 12, `${slide} ${item.kind} y`);
      }
    }
  });
});
