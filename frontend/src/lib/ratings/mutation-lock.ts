/**
 * 별점 저장·삭제가 같은 화면 갱신 안에서 겹치지 않게 하는 잠금.
 * React 상태(pending)는 다시 그리기 전에 보이지 않으므로, 잠금은 비동기 작업 전에 바로 획득한다.
 * 재조회가 끝나거나 오류로 빠져나온 뒤에만 해제한다.
 */
export function createRatingMutationLock() {
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
  };
}

export type RatingMutationLock = ReturnType<typeof createRatingMutationLock>;
