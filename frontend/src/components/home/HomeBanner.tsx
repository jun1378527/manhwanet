"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type PointerEvent } from "react";

import { stepAutoplay, wrapIndex } from "@/lib/home-banner/carousel";
import { BANNER_SLIDES } from "@/lib/home-banner/slides";

import type { BannerPhysics } from "./banner-physics";
import { BannerStaticArt } from "./BannerStaticArt";

/** off: 로드 전·움직임 줄이기(정적 장식), ready: 물리 동작, failed: 로드 실패(정적 장식 유지) */
type EngineState = "off" | "ready" | "failed";

const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

/**
 * 홈 대문 배너(디자인 시안 home-portal-v2).
 * - 제목·설명·링크·조작 버튼은 HTML 이고, 물리 캔버스는 장식(aria-hidden)이다.
 * - matter-js 는 브라우저에서 움직임 줄이기가 꺼져 있을 때만 동적 import 한다. 실패하면 정적 장식을 그대로 둔다.
 * - 자동 넘김(8초)은 정지 버튼, 마우스 진입, 배너 안 키보드 포커스, 화면 밖·백그라운드, 움직임 줄이기에서 멈춘다.
 */
export function HomeBanner() {
  const [index, setIndex] = useState(0);
  const [userPaused, setUserPaused] = useState(false);
  const [reducedMotion, setReducedMotion] = useState<boolean | null>(null);
  const [engine, setEngine] = useState<EngineState>("off");
  const [finePointer, setFinePointer] = useState(true);

  const rootRef = useRef<HTMLElement>(null);
  const sceneRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const barRefs = useRef<(HTMLSpanElement | null)[]>([]);
  const physicsRef = useRef<BannerPhysics | null>(null);
  const hoverRef = useRef(false);
  const focusRef = useRef(false);
  const visibleRef = useRef(true);
  const elapsedRef = useRef(0);
  const indexRef = useRef(0);

  const slide = BANNER_SLIDES[index];
  const count = BANNER_SLIDES.length;
  const autoplayOn = reducedMotion === false && !userPaused;

  /* 움직임 줄이기·포인터 종류 */
  useEffect(() => {
    const motion = window.matchMedia(REDUCED_MOTION);
    const pointer = window.matchMedia("(pointer: fine)");
    const sync = () => {
      setReducedMotion(motion.matches);
      setFinePointer(pointer.matches);
    };
    sync();
    motion.addEventListener("change", sync);
    pointer.addEventListener("change", sync);
    return () => {
      motion.removeEventListener("change", sync);
      pointer.removeEventListener("change", sync);
    };
  }, []);

  const syncRunning = useCallback(() => {
    physicsRef.current?.setRunning(visibleRef.current && !document.hidden);
  }, []);

  /* 물리 엔진: 움직임 줄이기가 꺼져 있을 때만 지연 로드 */
  useEffect(() => {
    if (reducedMotion !== false) {
      return;
    }
    let cancelled = false;
    let physics: BannerPhysics | null = null;
    Promise.all([import("matter-js"), import("./banner-physics")])
      .then(([matterModule, physicsModule]) => {
        const canvas = canvasRef.current;
        const scene = sceneRef.current;
        if (cancelled || !canvas || !scene) return;
        const Matter = (matterModule as unknown as { default?: typeof import("matter-js") }).default ?? matterModule;
        physics = physicsModule.createBannerPhysics(Matter, canvas, scene);
        physicsRef.current = physics;
        physics.setSlide(BANNER_SLIDES[indexRef.current].id);
        syncRunning();
        setEngine("ready");
      })
      .catch(() => {
        if (!cancelled) setEngine("failed");
      });
    return () => {
      cancelled = true;
      physics?.destroy();
      physicsRef.current = null;
      setEngine("off");
    };
  }, [reducedMotion, syncRunning]);

  /* 슬라이드가 바뀌면 시간·진행 막대를 처음부터, 물체를 새로 */
  useEffect(() => {
    indexRef.current = index;
    elapsedRef.current = 0;
    barRefs.current.forEach((bar, i) => {
      if (bar) bar.style.width = reducedMotion && i === index ? "100%" : "0%";
    });
    physicsRef.current?.setSlide(BANNER_SLIDES[index].id);
  }, [index, reducedMotion]);

  /* 화면 밖·백그라운드에서는 멈춘다 */
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const observer = new IntersectionObserver(
      (entries) => {
        visibleRef.current = entries.some((e) => e.isIntersecting);
        syncRunning();
      },
      { threshold: 0.15 },
    );
    observer.observe(root);
    document.addEventListener("visibilitychange", syncRunning);
    return () => {
      observer.disconnect();
      document.removeEventListener("visibilitychange", syncRunning);
    };
  }, [syncRunning]);

  /* 자동 넘김 */
  useEffect(() => {
    if (!autoplayOn) return;
    let frame = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const step = stepAutoplay(elapsedRef.current, now - last, {
        user: false,
        hover: hoverRef.current,
        focus: focusRef.current,
        reducedMotion: false,
        hidden: !visibleRef.current || document.hidden,
      });
      last = now;
      elapsedRef.current = step.elapsed;
      const bar = barRefs.current[indexRef.current];
      if (bar) bar.style.width = `${step.progress * 100}%`;
      if (step.advance) {
        setIndex((i) => wrapIndex(i + 1, count));
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [autoplayOn, count]);

  const go = (next: number, focusTab = false) => {
    const target = wrapIndex(next, count);
    setIndex(target);
    if (focusTab) tabRefs.current[target]?.focus();
  };

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const keys: Record<string, number> = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: count - 1 };
    if (event.key in keys) {
      event.preventDefault();
      go(keys[event.key], true);
    }
  };

  const onFocus = (event: FocusEvent<HTMLElement>) => {
    focusRef.current = (event.target as HTMLElement).matches(":focus-visible");
  };
  const onBlur = (event: FocusEvent<HTMLElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
      focusRef.current = false;
    }
  };
  const onPointerEnter = (event: PointerEvent<HTMLElement>) => {
    if (event.pointerType === "mouse") hoverRef.current = true;
  };
  const onPointerLeave = () => {
    hoverRef.current = false;
  };

  return (
    <section
      ref={rootRef}
      className="home-banner"
      data-slide={slide.id}
      aria-roledescription="carousel"
      aria-label="만화넷 안내 배너"
      onFocus={onFocus}
      onBlur={onBlur}
      onPointerEnter={onPointerEnter}
      onPointerLeave={onPointerLeave}
    >
      <div className="home-banner__copy">
        <div className="home-banner__slides" aria-live={autoplayOn ? "off" : "polite"}>
          {BANNER_SLIDES.map((s, i) => (
            <div
              key={s.id}
              id={`home-banner-panel-${s.id}`}
              className="home-banner__slide"
              role="tabpanel"
              aria-labelledby={`home-banner-tab-${s.id}`}
              hidden={i !== index}
            >
              <p className="home-banner__kicker">{s.kicker}</p>
              <h2 className="home-banner__title">
                {s.titleLines.map((line, n) => (
                  <span key={n} className="home-banner__title-line">
                    {line}
                  </span>
                ))}
              </h2>
              <p className="home-banner__desc">{s.description}</p>
              {s.points ? (
                <ul className="home-banner__points">
                  {s.points.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              ) : null}
              <Link className="home-banner__cta" href={s.cta.href}>
                {s.cta.label}
                <span aria-hidden="true"> →</span>
              </Link>
            </div>
          ))}
        </div>

        <div className="home-banner__controls">
          <button type="button" className="home-banner__icon-button" aria-label="이전 배너" onClick={() => go(index - 1)}>
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M10 3 5 8l5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className="home-banner__tabs" role="tablist" aria-label="배너 선택">
            {BANNER_SLIDES.map((s, i) => (
              <button
                key={s.id}
                ref={(el) => {
                  tabRefs.current[i] = el;
                }}
                type="button"
                role="tab"
                id={`home-banner-tab-${s.id}`}
                className="home-banner__tab"
                aria-selected={i === index}
                aria-controls={`home-banner-panel-${s.id}`}
                aria-label={s.kicker}
                tabIndex={i === index ? 0 : -1}
                onClick={() => go(i)}
                onKeyDown={onTabKeyDown}
              >
                <span className="home-banner__tab-label">
                  <span className="home-banner__tab-number" aria-hidden="true">
                    {String(i + 1).padStart(2, "0")}
                  </span>
                  {s.tabLabel}
                </span>
                <span className="home-banner__bar" aria-hidden="true">
                  <span
                    ref={(el) => {
                      barRefs.current[i] = el;
                    }}
                  />
                </span>
              </button>
            ))}
          </div>
          <button type="button" className="home-banner__icon-button" aria-label="다음 배너" onClick={() => go(index + 1)}>
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path d="m6 3 5 5-5 5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          {reducedMotion === true ? null : (
            <button
              type="button"
              className="home-banner__icon-button home-banner__pause"
              aria-pressed={userPaused}
              aria-label={userPaused ? "자동 넘김 다시 시작" : "자동 넘김 멈추기"}
              onClick={() => setUserPaused((p) => !p)}
            >
              {userPaused ? (
                <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
                  <path d="M4 2.5v9l7-4.5z" fill="currentColor" />
                </svg>
              ) : (
                <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
                  <rect x="3" y="2" width="3" height="10" rx="1" fill="currentColor" />
                  <rect x="8" y="2" width="3" height="10" rx="1" fill="currentColor" />
                </svg>
              )}
            </button>
          )}
        </div>
      </div>

      <div ref={sceneRef} className="home-banner__scene" data-engine={engine}>
        <BannerStaticArt slide={slide.id} />
        <canvas ref={canvasRef} className="home-banner__canvas" aria-hidden="true" />
        {engine === "ready" ? (
          <>
            <div className="home-banner__scene-tools">
              <button type="button" onClick={() => physicsRef.current?.shake()}>
                흔들기
              </button>
              <button type="button" onClick={() => physicsRef.current?.refill()}>
                다시 쏟기
              </button>
            </div>
            <p className="home-banner__hint" aria-hidden="true">
              {finePointer ? "끌어서 던져 보세요" : "톡 치면 튀어 올라요"}
            </p>
          </>
        ) : null}
      </div>
    </section>
  );
}
