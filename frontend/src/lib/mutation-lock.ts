/**
 * 화면의 변경 요청(저장·삭제)과 그 뒤 재조회가 같은 차례 안에서 겹치지 않게 하는 동기 잠금.
 * React 상태(pending)는 다시 그리기 전에 보이지 않으므로, 잠금은 비동기 작업 전에 바로 획득한다.
 * 재조회가 끝나거나 오류로 빠져나온 뒤에만 해제한다. 잠겨 있으면 작업을 실행하지 않고 undefined 를 돌려준다.
 */
export function createMutationLock() {
  let held = false;

  return {
    async run<T>(task: () => Promise<T>): Promise<T | undefined> {
      if (held) {
        return undefined;
      }
      held = true;
      try {
        return await task();
      } finally {
        held = false;
      }
    },
    /** 지금 잠겨 있는지 (버튼 비활성 등 표시용) */
    isHeld(): boolean {
      return held;
    },
  };
}

export type MutationLock = ReturnType<typeof createMutationLock>;
