"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useId, useRef, useState } from "react";

import { RatingSummaryLine } from "@/components/ratings/RatingSummaryLine";
import type { RatingSummary } from "@/lib/api/types";
import { loginHref } from "@/lib/auth/return-to";
import { USER_MISMATCH_CODE } from "@/lib/api/browser";
import { deleteMyRating, fetchMyRating, fetchRatingSummary, saveMyRating } from "@/lib/ratings/client-api";
import { createRatingMutationLock } from "@/lib/ratings/mutation-lock";
import {
  REFRESH_FAILED_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
  UNKNOWN_OUTCOME_MESSAGE,
  UNREACHABLE_MESSAGE,
  ratingFailure,
} from "@/lib/ratings/messages";
import { type MyRating, RATING_SCORE_HALVES, formatScore, scoreFromHalf } from "@/lib/ratings/score";

/** 서버가 넘겨 준 내 별점 상태 (lib/ratings/server 의 MyRatingState 와 같은 모양) */
export type InitialMyRating =
  | { status: "loaded"; rating: MyRating }
  | { status: "anonymous" }
  | { status: "unavailable" };

type Mine = InitialMyRating | { status: "expired" } | { status: "switched" };

type Notice = { tone: "success" | "error"; text: string; offerReload?: boolean };

type Props = {
  workId: string;
  initialSummary: RatingSummary;
  initialMine: InitialMyRating;
  /** 로그인 후 돌아올 현재 상세 주소 (returnTo 검증은 loginHref 가 한다) */
  selfHref: string;
  /** 이 화면을 렌더링한 사용자 ID. 변경 요청의 X-Expected-User-Id 로 고정해 보낸다 */
  viewerId: string | null;
};

/**
 * 작품 상세의 공개 별점 집계 + 내 별점(조회·선택·저장·변경·삭제).
 * - 선택은 라디오 그룹, 저장은 명시적 "저장" 버튼. 방향키로 선택을 바꿔도 요청을 보내지 않는다.
 * - 저장·삭제가 성공하면 내 별점과 공개 집계를 다시 조회한다. 재조회가 실패해도 변경은 성공으로 안내한다.
 * - 변경 요청은 자동으로 다시 보내지 않는다. 연결 실패로 결과를 모르면 현재 값만 다시 조회한다.
 * - 변경 요청은 렌더링 당시 사용자 ID를 헤더로 보낸다. 서버가 USER_MISMATCH 로 거절하면
 *   이전 사용자의 별점 상태를 지우고 새 사용자 기준으로 다시 그린다.
 * - 저장과 삭제는 다시 그리기 전에 잠겨, 재조회가 끝날 때까지 다른 변경을 받지 않는다.
 * - 변경이 성공하면 현재 주소를 유지한 채 라우터 캐시를 갱신한다. 뒤로가기 목록은 staleTimes 와 무관하게
 *   이전 집계를 재사용하므로, 성공 시점의 refresh 로 그 캐시를 비운다.
 */
export function WorkRatingSection({ workId, initialSummary, initialMine, selfHref, viewerId }: Props) {
  const router = useRouter();
  const legendId = useId();
  const errorId = useId();
  const mutationLock = useRef(createRatingMutationLock());
  /** 사용자 전환을 감지했으면 true. 이후 도착하는 응답으로 이전 사용자의 별점을 다시 그리지 않는다 */
  const switchedRef = useRef(false);
  const [summary, setSummary] = useState(initialSummary);
  const [mine, setMine] = useState<Mine>(initialMine);
  const [draft, setDraft] = useState<number | null>(
    initialMine.status === "loaded" ? initialMine.rating.score : null,
  );
  const [pending, setPending] = useState<"save" | "delete" | "reload" | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const saved = mine.status === "loaded" ? mine.rating.score : null;

  function applyMine(rating: MyRating) {
    setMine({ status: "loaded", rating });
    setDraft(rating.score);
  }

  /** 이전 사용자의 별점 상태를 지우고 새 사용자 기준으로 다시 렌더링한다 */
  function switchedViewer() {
    switchedRef.current = true;
    setMine({ status: "switched" });
    setDraft(null);
    setNotice(null);
    setFieldError(null);
    router.refresh();
  }

  /** 내 별점과 공개 집계를 다시 조회한다. 둘 중 하나라도 실패하면 false */
  async function reloadBoth(viewer: string): Promise<boolean> {
    if (switchedRef.current) {
      return false;
    }
    const [myResult, summaryResult] = await Promise.all([
      fetchMyRating(workId, viewer),
      fetchRatingSummary(workId),
    ]);
    if (switchedRef.current) {
      return false;
    }
    if (myResult.kind === "error" && myResult.code === USER_MISMATCH_CODE) {
      switchedViewer();
      return false;
    }
    let complete = true;
    if (myResult.kind === "ok") {
      applyMine(myResult.data);
    } else if (myResult.kind === "error" && myResult.status === 401) {
      setMine({ status: "expired" });
    } else {
      complete = false;
    }
    if (summaryResult.kind === "ok") {
      setSummary(summaryResult.data);
    } else {
      complete = false;
    }
    return complete;
  }

  async function afterSuccess(viewer: string, successText: string) {
    const complete = await reloadBoth(viewer);
    if (switchedRef.current) {
      return;
    }
    setNotice(
      complete
        ? { tone: "success", text: successText }
        : { tone: "success", text: `${successText} ${REFRESH_FAILED_MESSAGE}`, offerReload: true },
    );
  }

  async function afterFailure(viewer: string, result: Parameters<typeof ratingFailure>[0]) {
    const failure = ratingFailure(result);
    if (failure.kind === "account-switched") {
      switchedViewer();
      return;
    }
    if (failure.kind === "session-expired") {
      setMine({ status: "expired" });
      return;
    }
    if (failure.kind === "field") {
      setFieldError(failure.message);
      return;
    }
    if (failure.kind === "message") {
      setNotice({ tone: "error", text: failure.message });
      return;
    }
    // 반영 여부를 모른다: 변경 요청은 다시 보내지 않고 현재 값만 조회한다
    const reconciled = await reloadBoth(viewer);
    if (switchedRef.current) {
      return;
    }
    setNotice({ tone: "error", text: reconciled ? UNKNOWN_OUTCOME_MESSAGE : UNREACHABLE_MESSAGE, offerReload: !reconciled });
  }

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const viewer = viewerId;
    if (viewer === null || switchedRef.current || draft === null || draft === saved) {
      return;
    }
    const score = draft;
    const successText = saved === null ? "별점을 저장했습니다." : "별점을 변경했습니다.";
    await mutationLock.current.run(async () => {
      setPending("save");
      setNotice(null);
      setFieldError(null);
      try {
        const result = await saveMyRating(workId, viewer, score);
        if (switchedRef.current) {
          return;
        }
        if (result.kind === "ok") {
          applyMine(result.data);
          try {
            await afterSuccess(viewer, successText);
          } finally {
            if (!switchedRef.current) {
              // 재조회가 실패해도 변경 성공 안내는 이미 반영된 뒤다.
              // refresh 는 캐시 설정을 바꾸지 않고, 이미 쌓인 뒤로가기 항목을 한 번 무효화한다.
              router.refresh();
            }
          }
        } else {
          await afterFailure(viewer, result);
        }
      } finally {
        setPending(null);
      }
    });
  }

  async function handleDelete() {
    const viewer = viewerId;
    if (viewer === null || switchedRef.current || saved === null) {
      return;
    }
    await mutationLock.current.run(async () => {
      setPending("delete");
      setNotice(null);
      setFieldError(null);
      try {
        const result = await deleteMyRating(workId, viewer);
        if (switchedRef.current) {
          return;
        }
        if (result.kind === "ok") {
          setMine({ status: "loaded", rating: { workId, score: null, createdAt: null, updatedAt: null } });
          setDraft(null);
          try {
            await afterSuccess(viewer, "내 별점을 삭제했습니다.");
          } finally {
            if (!switchedRef.current) {
              router.refresh();
            }
          }
        } else {
          await afterFailure(viewer, result);
        }
      } finally {
        setPending(null);
      }
    });
  }

  async function handleReload() {
    const viewer = viewerId;
    if (viewer === null) {
      router.refresh();
      return;
    }
    await mutationLock.current.run(async () => {
      setPending("reload");
      try {
        const complete = await reloadBoth(viewer);
        if (switchedRef.current) {
          return;
        }
        setNotice(complete ? null : { tone: "error", text: REFRESH_FAILED_MESSAGE, offerReload: true });
      } finally {
        setPending(null);
      }
    });
  }

  const busy = pending !== null;

  return (
    <section className="work-rating" aria-labelledby={`${legendId}-heading`}>
      <h2 id={`${legendId}-heading`}>별점</h2>
      <RatingSummaryLine summary={summary} className="work-rating__summary" />

      {mine.status === "anonymous" || mine.status === "expired" ? (
        <p className="work-rating__login">
          {mine.status === "expired" ? SESSION_EXPIRED_MESSAGE : "별점을 남기려면 로그인하세요."}{" "}
          <Link href={loginHref(selfHref, mine.status === "expired" ? { expired: true } : undefined)}>로그인</Link>
        </p>
      ) : mine.status === "switched" ? (
        <p className="work-rating__status" role="status">
          로그인한 사용자가 바뀌어 화면을 다시 불러오는 중입니다…
        </p>
      ) : mine.status === "unavailable" ? (
        <p className="work-rating__status" role="alert">
          내 별점을 불러오지 못했습니다.{" "}
          <button type="button" className="link-button" onClick={handleReload} disabled={busy}>
            {pending === "reload" ? "불러오는 중…" : "다시 불러오기"}
          </button>
        </p>
      ) : (
        <form className="work-rating__form" onSubmit={handleSave} aria-busy={busy} noValidate>
          <fieldset
            className="rating-picker"
            aria-describedby={fieldError ? errorId : undefined}
            aria-invalid={fieldError ? true : undefined}
          >
            <legend id={legendId}>
              내 별점{" "}
              <span className="work-rating__saved">
                {saved === null ? "(아직 평가하지 않았습니다)" : `(저장된 별점 ${formatScore(saved)}점)`}
              </span>
            </legend>
            <div className="rating-picker__options">
              {RATING_SCORE_HALVES.map((half) => {
                const score = scoreFromHalf(half);
                return (
                  <label key={half} className="rating-picker__option">
                    <input
                      type="radio"
                      name="score"
                      value={formatScore(score)}
                      checked={draft === score}
                      onChange={() => {
                        setDraft(score);
                        setFieldError(null);
                      }}
                    />
                    <span className="rating-picker__label">
                      <span aria-hidden="true">★ </span>
                      {formatScore(score)}
                      <span className="visually-hidden">점</span>
                    </span>
                  </label>
                );
              })}
            </div>
            {fieldError ? (
              <p id={errorId} className="field__error">
                {fieldError}
              </p>
            ) : null}
          </fieldset>
          <div className="work-rating__actions">
            <button type="submit" className="button" disabled={busy || draft === null || draft === saved}>
              {pending === "save" ? "저장 중…" : saved === null ? "저장" : "변경 저장"}
            </button>
            {saved !== null ? (
              <button type="button" className="button button--secondary" onClick={handleDelete} disabled={busy}>
                {pending === "delete" ? "삭제 중…" : "내 별점 삭제"}
              </button>
            ) : null}
          </div>
        </form>
      )}

      {notice ? (
        <p
          className={`work-rating__notice work-rating__notice--${notice.tone}`}
          role={notice.tone === "error" ? "alert" : "status"}
        >
          {notice.text}
          {notice.offerReload ? (
            <>
              {" "}
              <button type="button" className="link-button" onClick={handleReload} disabled={busy}>
                {pending === "reload" ? "불러오는 중…" : "다시 불러오기"}
              </button>
            </>
          ) : null}
        </p>
      ) : null}
    </section>
  );
}
