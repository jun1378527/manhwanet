/**
 * 홈 배너 물리 장면(matter-js). 브라우저에서만 쓰며, matter-js 는 HomeBanner 가 동적 import 로 넘겨준다.
 * 캔버스는 장식이다. 모든 정보와 조작은 배너의 HTML 이 맡는다.
 *
 * - PC(정밀 포인터): 물체를 끌어서 던진다. 휠·터치 리스너는 떼어 내 페이지 스크롤을 막지 않는다.
 * - 터치: 톡 친 자리 주변 물체를 튀어 오르게 한다(preventDefault 없음, 세로 스크롤 유지).
 * - setRunning(false) 이면 계산·그리기를 멈춘다(화면 밖·백그라운드).
 * - destroy() 는 루프·타이머·리스너·관찰자·엔진을 모두 정리한다.
 */
import type MatterNS from "matter-js";

import {
  CHECK_FILL,
  COVER_PALETTES,
  planBodies,
  sceneScale,
  STAR_FILL,
  STAR_STROKE,
  type BodyPlan,
} from "@/lib/home-banner/bodies";
import type { BannerSlideId } from "@/lib/home-banner/slides";

type Matter = typeof MatterNS;

export type BannerPhysics = {
  setSlide(slide: BannerSlideId): void;
  shake(): void;
  refill(): void;
  setRunning(running: boolean): void;
  destroy(): void;
};

type Tagged = MatterNS.Body & { plugin: { plan?: BodyPlan; leaving?: boolean } };

const FONT = "system-ui, -apple-system, 'Segoe UI', 'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', sans-serif";
const STEP_MS = 1000 / 60;

export function createBannerPhysics(M: Matter, canvas: HTMLCanvasElement, container: HTMLElement): BannerPhysics {
  const { Engine, Bodies, Composite, Body, Mouse, MouseConstraint } = M;
  const engine = Engine.create({ gravity: { x: 0, y: 1, scale: 0.001 } });
  engine.positionIterations = 8;
  const world = engine.world;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    throw new Error("canvas 2d context unavailable");
  }

  let width = 0;
  let height = 0;
  let dpr = 1;
  let walls: MatterNS.Body[] = [];
  let timers: number[] = [];
  let frame = 0;
  let running = false;
  let destroyed = false;
  let slide: BannerSlideId = "explore";
  let mouse: MatterNS.Mouse | null = null;
  let mouseConstraint: MatterNS.MouseConstraint | null = null;
  const cleanups: (() => void)[] = [];

  const dynamicBodies = () => Composite.allBodies(world).filter((b) => !b.isStatic) as Tagged[];

  function resize() {
    const rect = container.getBoundingClientRect();
    width = Math.max(200, rect.width);
    height = Math.max(160, rect.height);
    dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    if (walls.length) {
      Composite.remove(world, walls);
    }
    const t = 80;
    const opts = { isStatic: true };
    walls = [
      Bodies.rectangle(width / 2, height + t / 2, width * 3, t, opts),
      Bodies.rectangle(-t / 2, -height, t, height * 4, opts),
      Bodies.rectangle(width + t / 2, -height, t, height * 4, opts),
    ];
    Composite.add(world, walls);
    for (const b of dynamicBodies()) {
      if (b.position.x > width - 10) {
        Body.setPosition(b, { x: width - 30, y: b.position.y });
      }
    }
    if (mouse) {
      mouse.pixelRatio = dpr;
    }
    draw();
  }

  function toBody(plan: BodyPlan): Tagged {
    let body: MatterNS.Body;
    switch (plan.kind) {
      case "cover":
        body = Bodies.rectangle(plan.x, plan.y, plan.w, plan.h, {
          chamfer: { radius: 5 }, restitution: 0.18, friction: 0.7, frictionAir: 0.012, angle: plan.angle,
        });
        break;
      case "star":
        body = Bodies.polygon(plan.x, plan.y, 10, plan.r, { restitution: 0.45, friction: 0.4, frictionAir: 0.01, angle: plan.angle });
        break;
      case "chip":
        body = Bodies.rectangle(plan.x, plan.y, plan.w, plan.h, {
          chamfer: { radius: plan.h / 2 }, restitution: 0.2, friction: 0.6, inertia: Infinity,
        });
        break;
      case "block":
        body = Bodies.rectangle(plan.x, plan.y, plan.w, plan.h, {
          chamfer: { radius: 12 }, restitution: 0, friction: 1, frictionStatic: 2, frictionAir: 0.02, inertia: Infinity,
        });
        break;
      case "check":
        body = Bodies.circle(plan.x, plan.y, plan.r, { restitution: 0.35, friction: 0.5, inertia: Infinity });
        break;
    }
    const tagged = body as Tagged;
    tagged.plugin = { plan };
    return tagged;
  }

  function clearTimers() {
    timers.forEach((id) => window.clearTimeout(id));
    timers = [];
  }

  function drop(next: BannerSlideId) {
    slide = next;
    clearTimers();
    // 이전 물체는 충돌을 끄고 바닥을 통과해 빠져나가며 사라진다.
    // 이미 바닥에 닿아 있는 접촉은 isSensor 만으로 풀리지 않으므로 충돌 마스크도 0 으로 둔다
    for (const b of dynamicBodies()) {
      b.isSensor = true;
      b.collisionFilter = { group: 0, category: 0x0002, mask: 0 };
      b.plugin.leaving = true;
      Body.setVelocity(b, { x: b.velocity.x, y: Math.max(b.velocity.y, 2) });
    }
    for (const plan of planBodies(next, width, height, Math.random)) {
      timers.push(window.setTimeout(() => {
        if (!destroyed) Composite.add(world, toBody(plan));
      }, plan.delayMs));
    }
  }

  /* ---------- 그리기 ---------- */
  function roundRect(x: number, y: number, w: number, h: number, r: number) {
    const c = ctx!;
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }

  function starPath(r: number) {
    const c = ctx!;
    c.beginPath();
    for (let i = 0; i < 10; i++) {
      const a = -Math.PI / 2 + (i * Math.PI) / 5;
      const d = i % 2 ? r * 0.46 : r;
      c.lineTo(Math.cos(a) * d, Math.sin(a) * d);
    }
    c.closePath();
  }

  function draw() {
    const c = ctx!;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, width, height);
    const k = sceneScale(width, height);
    for (const b of dynamicBodies()) {
      const plan = b.plugin.plan;
      if (!plan) continue;
      c.save();
      c.translate(b.position.x, b.position.y);
      c.rotate(b.angle);
      c.globalAlpha = b.plugin.leaving ? Math.max(0, Math.min(1, 1 - (b.position.y - height * 0.6) / (height * 0.5))) : 1;
      switch (plan.kind) {
        case "cover": {
          const [top, bottom, mark] = COVER_PALETTES[plan.palette];
          const g = c.createLinearGradient(0, -plan.h / 2, 0, plan.h / 2);
          g.addColorStop(0, top);
          g.addColorStop(1, bottom);
          c.shadowColor = "rgba(0,0,0,.35)";
          c.shadowBlur = 8;
          c.shadowOffsetY = 3;
          roundRect(-plan.w / 2, -plan.h / 2, plan.w, plan.h, 5);
          c.fillStyle = g;
          c.fill();
          c.shadowColor = "transparent";
          // 제목 글자 대신 무늬: 원 하나와 제목 자리 막대 두 줄
          c.fillStyle = mark;
          c.globalAlpha *= 0.85;
          c.beginPath();
          c.arc(plan.w * 0.12, -plan.h * 0.14, plan.w * 0.2, 0, Math.PI * 2);
          c.fill();
          c.fillStyle = "rgba(255,255,255,.75)";
          c.fillRect(-plan.w / 2 + 6, plan.h / 2 - 17, plan.w * 0.55, 4);
          c.fillRect(-plan.w / 2 + 6, plan.h / 2 - 10, plan.w * 0.35, 4);
          break;
        }
        case "star":
          starPath(plan.r * 1.15);
          c.fillStyle = STAR_FILL;
          c.fill();
          c.lineWidth = 1.5;
          c.strokeStyle = STAR_STROKE;
          c.stroke();
          break;
        case "chip":
          roundRect(-plan.w / 2, -plan.h / 2, plan.w, plan.h, plan.h / 2);
          c.fillStyle = plan.highlight ? STAR_FILL : "#2a2e37";
          c.fill();
          c.lineWidth = 1;
          c.strokeStyle = plan.highlight ? STAR_FILL : "rgba(255,255,255,.18)";
          c.stroke();
          c.fillStyle = plan.highlight ? "#15171c" : "#f2f3f5";
          c.font = `700 ${12.5 * k}px ${FONT}`;
          c.textAlign = "center";
          c.textBaseline = "middle";
          c.fillText(plan.label, 0, 1);
          break;
        case "block":
          roundRect(-plan.w / 2, -plan.h / 2, plan.w, plan.h, 12);
          c.fillStyle = plan.color;
          c.fill();
          c.fillStyle = "#123b2c";
          c.beginPath();
          c.arc(-plan.w / 2 + 20 * k, 0, 10 * k, 0, Math.PI * 2);
          c.fill();
          c.fillStyle = plan.color;
          c.font = `800 ${11 * k}px ${FONT}`;
          c.textAlign = "center";
          c.textBaseline = "middle";
          c.fillText(String(plan.step), -plan.w / 2 + 20 * k, 1);
          c.fillStyle = "#123b2c";
          c.font = `800 ${15 * k}px ${FONT}`;
          c.textAlign = "left";
          c.fillText(plan.label, -plan.w / 2 + 38 * k, 1);
          break;
        case "check":
          c.beginPath();
          c.arc(0, 0, plan.r, 0, Math.PI * 2);
          c.fillStyle = CHECK_FILL;
          c.fill();
          c.strokeStyle = "#0f3d2e";
          c.lineWidth = 2.6 * k;
          c.lineCap = "round";
          c.lineJoin = "round";
          c.beginPath();
          c.moveTo(-plan.r * 0.42, 0);
          c.lineTo(-plan.r * 0.1, plan.r * 0.32);
          c.lineTo(plan.r * 0.45, -plan.r * 0.3);
          c.stroke();
          break;
      }
      c.restore();
    }
  }

  function loop() {
    frame = 0;
    if (!running || destroyed) return;
    Engine.update(engine, STEP_MS);
    for (const b of dynamicBodies()) {
      if (b.plugin.leaving && b.position.y > height + 120) {
        Composite.remove(world, b);
      }
    }
    draw();
    frame = window.requestAnimationFrame(loop);
  }

  /* ---------- 입력 ---------- */
  const finePointer = window.matchMedia("(pointer: fine)").matches;
  if (finePointer) {
    mouse = Mouse.create(canvas);
    mouse.pixelRatio = Math.min(2, window.devicePixelRatio || 1);
    const m = mouse as MatterNS.Mouse & Record<"mousemove" | "mousedown" | "mouseup" | "mousewheel", EventListener>;
    // 휠·터치는 matter-js 가 preventDefault 하므로 떼어 낸다(페이지 스크롤 유지). 터치는 아래 pointerdown 이 맡는다
    canvas.removeEventListener("wheel", m.mousewheel);
    canvas.removeEventListener("touchmove", m.mousemove);
    canvas.removeEventListener("touchstart", m.mousedown);
    canvas.removeEventListener("touchend", m.mouseup);
    mouseConstraint = MouseConstraint.create(engine, { mouse, constraint: { stiffness: 0.18, render: { visible: false } } as MatterNS.IConstraintDefinition });
    Composite.add(world, mouseConstraint);
    cleanups.push(() => {
      canvas.removeEventListener("mousemove", m.mousemove);
      canvas.removeEventListener("mousedown", m.mousedown);
      canvas.removeEventListener("mouseup", m.mouseup);
    });
  }
  const onPointerDown = (event: PointerEvent) => {
    if (event.pointerType === "mouse") return;
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    for (const b of dynamicBodies()) {
      const dx = b.position.x - x;
      const dy = b.position.y - y;
      const d = Math.hypot(dx, dy) || 1;
      if (d < 140) {
        Body.setVelocity(b, { x: b.velocity.x + (dx / d) * 6, y: b.velocity.y - 9 * (1 - d / 160) });
      }
    }
  };
  canvas.addEventListener("pointerdown", onPointerDown, { passive: true });
  cleanups.push(() => canvas.removeEventListener("pointerdown", onPointerDown));

  const observer = new ResizeObserver(() => resize());
  observer.observe(container);
  cleanups.push(() => observer.disconnect());
  resize();

  return {
    setSlide(next) {
      if (destroyed) return;
      drop(next);
    },
    shake() {
      for (const b of dynamicBodies()) {
        if (b.plugin.leaving) continue;
        Body.setVelocity(b, { x: (Math.random() - 0.5) * 12, y: -8 - Math.random() * 7 });
        if (b.inertia !== Infinity) Body.setAngularVelocity(b, (Math.random() - 0.5) * 0.5);
      }
    },
    refill() {
      if (!destroyed) drop(slide);
    },
    setRunning(next) {
      if (destroyed || running === next) return;
      running = next;
      if (running && !frame) {
        frame = window.requestAnimationFrame(loop);
      } else if (!running && frame) {
        window.cancelAnimationFrame(frame);
        frame = 0;
      }
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      running = false;
      if (frame) window.cancelAnimationFrame(frame);
      clearTimers();
      cleanups.forEach((fn) => fn());
      if (mouseConstraint) Composite.remove(world, mouseConstraint);
      Composite.clear(world, false);
      Engine.clear(engine);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    },
  };
}
