/** 홈 배너 자동 넘김 계산(브라우저 API 없이 테스트할 수 있게 분리). */

export const AUTOPLAY_MS = 8000;

/** 한 프레임 간격 상한. 탭 전환·디버거 정지 뒤 큰 간격이 한 번에 넘어가지 않게 한다 */
export const MAX_FRAME_MS = 100;

export function wrapIndex(index: number, count: number): number {
  if (count <= 0) {
    return 0;
  }
  return ((index % count) + count) % count;
}

/** 자동 넘김을 멈추는 이유. 하나라도 참이면 시간이 흐르지 않는다 */
export type PauseReasons = {
  /** 사용자가 정지 버튼을 눌렀다 */
  user: boolean;
  /** 마우스가 배너 위에 있다 */
  hover: boolean;
  /** 키보드 포커스가 배너 안에 있다 */
  focus: boolean;
  /** OS 움직임 줄이기 설정 */
  reducedMotion: boolean;
  /** 배너가 화면 밖이거나 문서가 백그라운드다 */
  hidden: boolean;
};

export function isAutoplayPaused(reasons: PauseReasons): boolean {
  return reasons.user || reasons.hover || reasons.focus || reasons.reducedMotion || reasons.hidden;
}

export type AutoplayStep = {
  /** 이번 슬라이드에서 흐른 시간 */
  elapsed: number;
  /** 0~1 진행률 */
  progress: number;
  /** 다음 슬라이드로 넘어가야 하는가 */
  advance: boolean;
};

/**
 * 한 프레임만큼 시간을 진행한다. 멈춘 동안에는 elapsed 가 그대로다(재개하면 남은 시간부터).
 * 넘어갈 때 elapsed 는 0 으로 돌아간다.
 */
export function stepAutoplay(elapsed: number, frameMs: number, reasons: PauseReasons, durationMs = AUTOPLAY_MS): AutoplayStep {
  if (isAutoplayPaused(reasons) || !(frameMs > 0)) {
    return { elapsed, progress: Math.min(1, elapsed / durationMs), advance: false };
  }
  const next = elapsed + Math.min(frameMs, MAX_FRAME_MS);
  if (next >= durationMs) {
    return { elapsed: 0, progress: 1, advance: true };
  }
  return { elapsed: next, progress: next / durationMs, advance: false };
}
