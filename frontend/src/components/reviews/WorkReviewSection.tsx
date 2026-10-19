"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useEffect, useId, useRef, useState } from "react";

import { fetchSession } from "@/lib/auth/client-api";
import { loginHref } from "@/lib/auth/return-to";
import { createMutationLock } from "@/lib/mutation-lock";
import { REVIEW_MAX_LENGTH, checkReviewBody } from "@/lib/reviews/body";
import { createMyReview, deleteMyReview, fetchMyReview, fetchReviewPage, updateMyReview } from "@/lib/reviews/client-api";
import {
  REVIEW_REFRESH_FAILED_MESSAGE,
  REVIEW_SESSION_CHECK_FAILED_MESSAGE,
  REVIEW_SESSION_EXPIRED_MESSAGE,
  REVIEW_UNREACHABLE_MESSAGE,
  type ReviewFailure,
  reviewFailure,
} from "@/lib/reviews/messages";
import type { MyReview } from "@/lib/reviews/model";
import { type Content, reconcileDelete, reconcileWrite, sameReview } from "@/lib/reviews/reconcile";
import type { MyReviewState, ReviewPageState } from "@/lib/reviews/server";
import { type WorksQuery, workDetailHrefFor } from "@/lib/works/search";
import { PublicReviewList } from "./PublicReviewList";
import { ReviewMeta } from "./ReviewMeta";

type Props = {
  workId: string;
  /** 서버 렌더링 시점의 로그인 사용자 ID. 비로그인·확인 실패면 null */
  viewerId: string | null;
  initialMine: MyReviewState;
  initialList: ReviewPageState;
  /** 1부터 시작하는 공개 리뷰 페이지 (URL ?reviewPage) */
  reviewPage: number;
  /** 작품 목록 복귀 상태(검색어·필터·정렬·페이지). 리뷰 페이지 주소에 그대로 이어 붙인다 */
  listQuery: WorksQuery;
};

type Mine = MyReviewState | { status: "switched" };
type Notice = { tone: "success" | "error" | "info"; text: string; offerReload?: boolean };
type FieldErrors = { body: string | null; spoiler: string | null; other: string | null };
/**
 * 충돌: 작성 중인 글을 지우지 않고 최신 리뷰를 함께 보여 준다. 사용자가 고르기 전에는 아무 요청도 보내지 않는다.
 * latest: undefined 이면 최신 리뷰를 아직 불러오지 못한 상태
 */
type Conflict = { reason: "changed" | "exists" | "missing"; latest: MyReview | null | undefined };
type Pending = "save" | "delete" | "reload" | null;

const EMPTY_DRAFT: Content = { body: "", containsSpoiler: false };
const NO_FIELD_ERRORS: FieldErrors = { body: null, spoiler: null, other: null };

/** 같은 페이지의 목록 재조회가 실패해 마지막으로 불러온 목록을 그대로 둘 때 (#24) */
const STALE_LIST_MESSAGE =
  "최신 리뷰 목록을 확인하지 못했습니다. 아래에 보이는 목록은 이 페이지에서 마지막으로 불러온 내용입니다.";

const CONFLICT_TEXT: Record<Conflict["reason"], string> = {
  changed: "다른 곳에서 리뷰가 수정되었거나 삭제 후 다시 작성되었습니다. 작성 중인 글은 그대로 두었습니다.",
  exists: "이미 이 작품에 작성한 리뷰가 있습니다(다른 탭이나 기기에서 작성했을 수 있습니다). 작성 중인 글은 그대로 두었습니다.",
  missing: "수정하려던 리뷰가 이미 삭제되었습니다. 작성 중인 글은 그대로 두었습니다.",
};

/**
 * 작품 상세의 리뷰 영역: 내 리뷰(조회·작성·수정·삭제) + 공개 리뷰 목록.
 *
 * - 저장·삭제·재조회는 하나의 동기 잠금을 공유한다. 같은 차례에 두 번 눌러도 요청은 하나만 나간다.
 * - 변경 요청은 자동으로 다시 보내지 않는다. 연결 실패·5xx 로 결과를 모르면 현재 리뷰만 다시 읽어 반영 여부를 판단한다.
 * - 409·404 충돌에서는 작성 중인 글을 보존하고 최신 리뷰를 보여 준다. 덮어쓰기는 사용자가 명시적으로 고를 때만 한다.
 * - 변경 요청 전 GET /auth/me 로 화면을 렌더링한 사용자와 같은지 확인한다. 다른 사용자로 바뀌었으면 요청하지 않고
 *   이전 사용자의 글·응답을 지운 뒤 새로 렌더링한다. 세션 만료는 작성 중인 글을 유지한다.
 * - 작성 중인 글은 메모리(React 상태)에만 둔다. 브라우저 저장소·URL·로그에 남기지 않는다.
 * - 변경이 성공하면 내 리뷰와 공개 목록을 다시 읽고, router.refresh 로 라우터 캐시(뒤로가기 포함)를 갱신한다.
 */
export function WorkReviewSection({ workId, viewerId, initialMine, initialList, reviewPage, listQuery }: Props) {
  const router = useRouter();
  const headingId = useId();
  const listHeadingId = useId();
  const textareaId = useId();
  const hintId = useId();
  const bodyErrorId = useId();
  const spoilerErrorId = useId();
  const leaveTitleId = useId();
  const lock = useRef(createMutationLock());
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const deleteButtonRef = useRef<HTMLButtonElement>(null);
  const stayButtonRef = useRef<HTMLButtonElement>(null);
  /** 사용자 전환을 감지했으면 true. 이후 도착하는 응답으로 화면 상태를 바꾸지 않는다 */
  const switchedRef = useRef(false);

  const [mine, setMine] = useState<Mine>(initialMine);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [editing, setEditing] = useState(false);
  /** 아직 리뷰가 없을 때 "리뷰 쓰기"로 편집기를 열었는지. 처음에는 닫혀 있어 작품 정보보다 큰 폼이 먼저 나오지 않는다 */
  const [composing, setComposing] = useState(false);
  /** 작성 중인 새 리뷰를 "취소"로 닫으려 할 때 버릴지 묻는 중인지 */
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);
  const discardRef = useRef<HTMLButtonElement>(null);
  const [draft, setDraft] = useState<Content>(EMPTY_DRAFT);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>(NO_FIELD_ERRORS);
  const [conflict, setConflict] = useState<Conflict | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [pending, setPending] = useState<Pending>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  // 공개 목록: 서버가 새 props 를 주면(리뷰 페이지 이동, router.refresh) 그 값을 쓰고,
  // 변경 뒤 브라우저에서 다시 읽은 값은 같은 페이지 안에서만 덮어쓴다.
  const [list, setList] = useState({ source: initialList, state: initialList, page: reviewPage, workId });
  const [listLoading, setListLoading] = useState(false);
  const [listNotice, setListNotice] = useState<string | null>(null);
  /** 앱 안 링크 이동을 확인 중이면 이동할 주소. null 이면 대화 상자가 없다 (#23) */
  const [leaveHref, setLeaveHref] = useState<string | null>(null);
  if (list.source !== initialList || list.workId !== workId || list.page !== reviewPage) {
    // 같은 작품·같은 페이지의 재조회만 실패했을 때 마지막 목록을 유지한다. 다른 작품·페이지 목록은 현재 목록으로 쓰지 않는다.
    const keepCurrent =
      initialList.status === "unavailable" &&
      list.state.status === "loaded" &&
      list.workId === workId &&
      list.page === reviewPage;
    setList({ source: initialList, state: keepCurrent ? list.state : initialList, page: reviewPage, workId });
    setListNotice(keepCurrent ? STALE_LIST_MESSAGE : null);
  }

  const review = mine.status === "loaded" ? mine.review : null;
  const formOpen = mine.status === "loaded" && (review === null ? composing : editing);
  const check = checkReviewBody(draft.body);
  const dirty =
    formOpen &&
    (review === null
      ? draft.body.length > 0 || draft.containsSpoiler
      : !(check.ok && check.body === review.body && draft.containsSpoiler === review.containsSpoiler));
  const busy = pending !== null;

  const hrefFor = (page: number) => workDetailHrefFor(workId, listQuery, page);
  const selfHref = hrefFor(reviewPage);

  // 저장하지 않은 글이 있으면 문서가 내려갈 때(새로고침·탭 닫기) 브라우저 경고를 띄운다.
  // 브라우저 뒤로·앞으로는 beforeunload 가 나지 않고, 여기서 히스토리를 가로채지 않는다(#23).
  useEffect(() => {
    if (!dirty) {
      return;
    }
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  // 같은 탭의 앱 안 링크 클릭만 확인한다. 새 탭·다른 사이트·수정키 클릭은 현재 화면의 글을 지우지 않는다.
  useEffect(() => {
    if (!dirty) {
      return;
    }
    function onClick(event: MouseEvent) {
      if (event.defaultPrevented || event.button !== 0) {
        return;
      }
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      const target = event.target;
      if (!(target instanceof Element)) {
        return;
      }
      const anchor = target.closest("a");
      if (!(anchor instanceof HTMLAnchorElement)) {
        return;
      }
      if (anchor.target && anchor.target !== "_self") {
        return;
      }
      if (anchor.hasAttribute("download")) {
        return;
      }
      const raw = anchor.getAttribute("href");
      if (raw === null || raw.startsWith("#")) {
        return;
      }
      let url: URL;
      try {
        url = new URL(anchor.href);
      } catch {
        return;
      }
      if (url.origin !== window.location.origin) {
        return;
      }
      const next = `${url.pathname}${url.search}${url.hash}`;
      const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
      if (next === current) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      setLeaveHref(next);
    }
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, [dirty]);

  useEffect(() => {
    if (leaveHref === null) {
      return;
    }
    stayButtonRef.current?.focus();
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setLeaveHref(null);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [leaveHref]);

  useEffect(() => {
    if (confirmingDelete) {
      confirmRef.current?.focus();
    }
  }, [confirmingDelete]);

  // 상단 "리뷰 쓰기"(#write-review) 진입: 주소로 들어왔거나 같은 화면의 링크를 누르면 편집기를 연다.
  // 리뷰가 이미 있으면 formOpen 이 composing 을 보지 않으므로 내 리뷰가 그대로 보인다.
  useEffect(() => {
    function open() {
      setComposing(true);
      requestAnimationFrame(() => textareaRef.current?.focus({ preventScroll: true }));
    }
    function onHash() {
      if (window.location.hash === "#write-review") {
        open();
      }
    }
    onHash();
    function onClick(event: MouseEvent) {
      const target = event.target;
      if (event.defaultPrevented || !(target instanceof Element)) {
        return;
      }
      const anchor = target.closest("a");
      if (anchor instanceof HTMLAnchorElement && anchor.getAttribute("href") === "#write-review") {
        open();
      }
    }
    document.addEventListener("click", onClick);
    window.addEventListener("hashchange", onHash);
    return () => {
      document.removeEventListener("click", onClick);
      window.removeEventListener("hashchange", onHash);
    };
  }, []);

  // ---------- 사용자 확인 ----------

  /** 이전 사용자의 글·응답을 지우고 새 사용자 기준으로 다시 렌더링한다 */
  function switchedViewer() {
    switchedRef.current = true;
    setMine({ status: "switched" });
    setDraft(EMPTY_DRAFT);
    setEditing(false);
    setComposing(false);
    setConfirmingDiscard(false);
    setConflict(null);
    setConfirmingDelete(false);
    setLeaveHref(null);
    setFieldErrors(NO_FIELD_ERRORS);
    setNotice(null);
    router.refresh();
  }

  /**
   * 요청 전에 화면을 렌더링한 사용자와 현재 세션 사용자가 같은지 미리 확인한다(빠른 안내용).
   * 이 확인과 실제 요청 사이에 계정이 바뀔 수 있으므로 보장은 서버가 한다: 모든 내 리뷰 요청은
   * X-Expected-User-Id 를 보내고, 서버가 같은 요청의 세션 사용자와 다르면 USER_MISMATCH 로 거절한다(#22).
   */
  async function ensureViewer(): Promise<boolean> {
    const session = await fetchSession();
    if (session.status === "unavailable") {
      setNotice({ tone: "error", text: REVIEW_SESSION_CHECK_FAILED_MESSAGE });
      return false;
    }
    if (session.status === "anonymous") {
      setSessionExpired(true);
      return false;
    }
    if (session.user.id !== viewerId) {
      switchedViewer();
      return false;
    }
    setSessionExpired(false);
    return true;
  }

  // ---------- 조회 ----------

  type Latest = { kind: "ok"; review: MyReview | null } | { kind: "expired" } | { kind: "failed" } | { kind: "switched" };

  async function readLatest(): Promise<Latest> {
    if (viewerId === null) {
      return { kind: "failed" };
    }
    const result = await fetchMyReview(workId, viewerId);
    if (switchedRef.current) {
      return { kind: "switched" };
    }
    if (result.kind === "ok" && result.data.workId === workId) {
      return { kind: "ok", review: result.data.review };
    }
    if (result.kind === "error" && result.code === "USER_MISMATCH") {
      // 다른 사용자의 내 리뷰를 이 화면에 보여 주지 않는다
      switchedViewer();
      return { kind: "switched" };
    }
    if (result.kind === "error" && result.status === 401) {
      setSessionExpired(true);
      return { kind: "expired" };
    }
    return { kind: "failed" };
  }

  /**
   * 공개 목록의 현재 페이지를 다시 읽는다. 삭제 등으로 현재 페이지가 비면 마지막 유효 페이지로 옮긴다.
   * @returns 성공 여부, 페이지를 옮겼는지(옮기면 그 이동이 서버 렌더링으로 목록을 새로 가져온다)
   */
  async function reloadList(): Promise<{ ok: boolean; moved: boolean }> {
    setListLoading(true);
    try {
      const result = await fetchReviewPage(workId, reviewPage - 1);
      if (result.kind !== "ok") {
        setListNotice("공개 리뷰 목록을 불러오지 못했습니다.");
        return { ok: false, moved: false };
      }
      const data = result.data;
      if (data.content.length === 0 && reviewPage > 1) {
        router.replace(hrefFor(Math.max(1, data.totalPages)), { scroll: false });
        return { ok: true, moved: true };
      }
      setListNotice(null);
      setList((current) => ({ ...current, state: { status: "loaded", page: data }, page: reviewPage, workId }));
      return { ok: true, moved: false };
    } finally {
      setListLoading(false);
    }
  }

  /** 저장하지 않은 글이 있고 최신 리뷰가 기준과 다르면 충돌로 보여 주고, 아니면 최신 리뷰를 그대로 쓴다 */
  function applyLatest(latest: MyReview | null) {
    if (dirty && !sameReview(review, latest)) {
      setConflict({ reason: "changed", latest });
      return;
    }
    setMine({ status: "loaded", review: latest });
    if (!dirty) {
      setEditing(false);
      setDraft(EMPTY_DRAFT);
      if (latest !== null) {
        setComposing(false);
      }
    }
  }

  // ---------- 성공·실패 처리 ----------

  async function afterChange(successText: string, next: MyReview | null) {
    setMine({ status: "loaded", review: next });
    setEditing(false);
    setComposing(false);
    setConfirmingDiscard(false);
    setDraft(EMPTY_DRAFT);
    setConflict(null);
    setConfirmingDelete(false);
    setFieldErrors(NO_FIELD_ERRORS);

    const [latest, listResult] = await Promise.all([readLatest(), reloadList()]);
    if (latest.kind === "switched" || switchedRef.current) {
      return; // 변경은 원래 사용자에게 반영됐지만, 새 사용자에게 이전 사용자의 결과를 보여 주지 않는다
    }
    if (latest.kind === "ok") {
      setMine({ status: "loaded", review: latest.review });
    }
    const complete = latest.kind === "ok" && listResult.ok;
    setNotice(
      complete || latest.kind === "expired"
        ? { tone: "success", text: successText }
        : { tone: "success", text: `${successText} ${REVIEW_REFRESH_FAILED_MESSAGE}`, offerReload: true },
    );
    if (!listResult.moved) {
      // 변경 결과를 라우터 캐시에 반영한다(뒤로가기로 돌아온 화면이 이전 목록을 보이지 않게). 별점과 같은 방식
      router.refresh();
    }
  }

  async function openConflict(reason: Conflict["reason"]) {
    setConflict({ reason, latest: undefined });
    const latest = await readLatest();
    if (latest.kind === "ok" && !switchedRef.current) {
      setConflict({ reason, latest: latest.review });
    }
  }

  async function handleWriteFailure(failure: ReviewFailure, base: MyReview | null, intended: Content) {
    switch (failure.kind) {
      case "account-switched":
        // 서버가 사용자 불일치로 거절했다(아무것도 바뀌지 않음). 재시도하지 않고 이전 사용자의 글·충돌 상태를 지운다
        switchedViewer();
        return;
      case "session-expired":
        setSessionExpired(true);
        return;
      case "field":
        setFieldErrors({ body: failure.body, spoiler: failure.spoiler, other: failure.other });
        textareaRef.current?.focus();
        return;
      case "message":
        setNotice({ tone: "error", text: failure.message });
        return;
      case "already-exists":
        await openConflict("exists");
        return;
      case "version-conflict":
        await openConflict("changed");
        return;
      case "review-missing":
        await openConflict("missing");
        return;
      case "unknown-outcome": {
        const latest = await readLatest();
        if (latest.kind !== "ok") {
          if (latest.kind === "failed") {
            setNotice({ tone: "info", text: REVIEW_UNREACHABLE_MESSAGE, offerReload: true });
          }
          return;
        }
        const decision = reconcileWrite(base, intended, latest.review);
        if (decision === "applied") {
          await afterChange("서버 응답이 늦었지만 리뷰가 저장된 것을 확인했습니다.", latest.review);
        } else if (decision === "not-applied") {
          setNotice({ tone: "error", text: "리뷰가 저장되지 않았습니다. 작성 중인 글은 그대로 있습니다. 다시 저장해 주세요." });
        } else {
          setConflict({ reason: "changed", latest: latest.review });
        }
        return;
      }
    }
  }

  // ---------- 동작 ----------

  /**
   * 작성(base 가 null) 또는 수정. base 는 기본적으로 현재 내 리뷰이고,
   * 충돌 화면에서 사용자가 "덮어쓰기"·"새 리뷰로 등록"을 고르면 그때 확인한 최신 리뷰(또는 null)를 명시적으로 넘긴다.
   */
  async function save(base: MyReview | null) {
    const viewer = viewerId;
    if (viewer === null || switchedRef.current) {
      return;
    }
    const checked = checkReviewBody(draft.body);
    if (!checked.ok) {
      setFieldErrors({ ...NO_FIELD_ERRORS, body: checked.reason });
      textareaRef.current?.focus();
      return;
    }
    const intended: Content = { body: checked.body, containsSpoiler: draft.containsSpoiler };
    const raw: Content = { body: draft.body, containsSpoiler: draft.containsSpoiler };
    await lock.current.run(async () => {
      setPending("save");
      setNotice(null);
      setFieldErrors(NO_FIELD_ERRORS);
      try {
        if (!(await ensureViewer())) {
          return;
        }
        const result =
          base === null
            ? await createMyReview(workId, viewer, raw.body, raw.containsSpoiler)
            : await updateMyReview(workId, viewer, { reviewId: base.id, version: base.version }, raw.body, raw.containsSpoiler);
        if (switchedRef.current) {
          return;
        }
        if (result.kind === "ok") {
          setConflict(null);
          await afterChange(base === null ? "리뷰를 등록했습니다." : "리뷰를 수정했습니다.", result.data.review);
        } else {
          await handleWriteFailure(reviewFailure(result), base, intended);
        }
      } finally {
        setPending(null);
      }
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (conflict !== null || !dirty) {
      return;
    }
    await save(review);
  }

  async function handleDelete() {
    const base = review;
    const viewer = viewerId;
    if (base === null || viewer === null || switchedRef.current) {
      return;
    }
    await lock.current.run(async () => {
      setPending("delete");
      setNotice(null);
      try {
        if (!(await ensureViewer())) {
          return;
        }
        const result = await deleteMyReview(workId, viewer, { reviewId: base.id, version: base.version });
        if (switchedRef.current) {
          return;
        }
        if (result.kind === "ok") {
          await afterChange("리뷰를 삭제했습니다.", null);
          return;
        }
        setConfirmingDelete(false);
        const failure = reviewFailure(result);
        if (failure.kind === "account-switched") {
          // 서버가 사용자 불일치로 거절했다. 삭제된 것처럼 진행하지 않는다
          switchedViewer();
          return;
        }
        if (failure.kind === "session-expired") {
          setSessionExpired(true);
          return;
        }
        if (failure.kind === "version-conflict" || failure.kind === "unknown-outcome") {
          const latest = await readLatest();
          if (latest.kind !== "ok") {
            if (latest.kind === "failed") {
              setNotice({ tone: "info", text: REVIEW_UNREACHABLE_MESSAGE, offerReload: true });
            }
            return;
          }
          const decision = failure.kind === "version-conflict" ? "diverged" : reconcileDelete(base, latest.review);
          if (decision === "applied") {
            await afterChange("서버 응답이 늦었지만 리뷰가 삭제된 것을 확인했습니다.", null);
            return;
          }
          setMine({ status: "loaded", review: latest.review });
          setNotice({
            tone: "error",
            text:
              decision === "not-applied"
                ? "리뷰를 삭제하지 못했습니다. 다시 시도해 주세요."
                : "다른 곳에서 리뷰가 수정되었거나 다시 작성되어 삭제하지 않았습니다. 아래 최신 리뷰를 확인한 뒤 다시 삭제해 주세요.",
          });
          return;
        }
        setNotice({ tone: "error", text: failure.kind === "message" ? failure.message : "리뷰를 삭제하지 못했습니다." });
      } finally {
        setPending(null);
      }
    });
  }

  async function handleReload() {
    if (viewerId === null) {
      // 로그인 상태를 확인하지 못한 채 렌더링된 화면: 서버에서 다시 렌더링해 사용자를 정한다
      router.refresh();
      return;
    }
    await lock.current.run(async () => {
      setPending("reload");
      try {
        if (!(await ensureViewer())) {
          return;
        }
        const [latest, listResult] = await Promise.all([readLatest(), reloadList()]);
        if (latest.kind === "ok") {
          applyLatest(latest.review);
        }
        setNotice(
          latest.kind === "failed" || !listResult.ok
            ? { tone: "error", text: REVIEW_REFRESH_FAILED_MESSAGE, offerReload: true }
            : null,
        );
      } finally {
        setPending(null);
      }
    });
  }

  async function handleListReload() {
    await lock.current.run(async () => {
      setPending("reload");
      try {
        await reloadList();
      } finally {
        setPending(null);
      }
    });
  }

  async function handleConflictReload() {
    if (conflict === null) {
      return;
    }
    await lock.current.run(async () => {
      setPending("reload");
      try {
        if (await ensureViewer()) {
          await openConflict(conflict.reason);
        }
      } finally {
        setPending(null);
      }
    });
  }

  /** 충돌: 작성 중인 글을 버리고 최신 리뷰를 보여 준다 */
  function takeLatest() {
    if (conflict === null || conflict.latest === undefined) {
      return;
    }
    setMine({ status: "loaded", review: conflict.latest });
    setConflict(null);
    setEditing(false);
    setComposing(conflict.latest === null);
    setDraft(EMPTY_DRAFT);
    setFieldErrors(NO_FIELD_ERRORS);
    setNotice({ tone: "success", text: "최신 리뷰를 불러왔습니다. 작성 중이던 글은 버렸습니다." });
  }

  /** 충돌: 사용자가 명시적으로 고른 경우에만 최신 리뷰를 기준으로 작성 중인 글을 저장한다 */
  async function saveOverLatest() {
    if (conflict === null || conflict.latest === undefined) {
      return;
    }
    await save(conflict.latest);
  }

  function startEdit() {
    if (review === null) {
      return;
    }
    setDraft({ body: review.body, containsSpoiler: review.containsSpoiler });
    setEditing(true);
    setNotice(null);
    setFieldErrors(NO_FIELD_ERRORS);
    requestAnimationFrame(() => textareaRef.current?.focus());
  }

  /** 아직 리뷰가 없을 때 편집기를 연다(작성 중이던 글이 있으면 그대로 이어서 쓴다) */
  function startCompose() {
    setComposing(true);
    setNotice(null);
    requestAnimationFrame(() => textareaRef.current?.focus());
  }

  function stayOnPage() {
    setLeaveHref(null);
    requestAnimationFrame(() => textareaRef.current?.focus());
  }

  function followLeaveLink() {
    const href = leaveHref;
    if (href === null) {
      return;
    }
    setLeaveHref(null);
    router.push(href);
  }

  function cancelEdit() {
    // 새 리뷰를 쓰는 중이면 글을 바로 지우지 않고 한 번 묻는다
    if (review === null && dirty && !confirmingDiscard) {
      setConfirmingDiscard(true);
      requestAnimationFrame(() => discardRef.current?.focus());
      return;
    }
    setConfirmingDiscard(false);
    setEditing(false);
    setComposing(false);
    setDraft(EMPTY_DRAFT);
    setFieldErrors(NO_FIELD_ERRORS);
    setConflict(null);
  }

  // ---------- 화면 ----------

  const describedBy = [hintId, fieldErrors.body ? bodyErrorId : null].filter(Boolean).join(" ");
  const listState = list.state;
  const myReviewId = review?.id ?? null;

  return (
    <section className="work-reviews" aria-labelledby={headingId}>
      <h2 id={headingId}>리뷰</h2>
      {leaveHref !== null ? (
        <div className="my-review__confirm" role="alertdialog" aria-modal="true" aria-labelledby={leaveTitleId}>
          <p id={leaveTitleId}>작성 중인 리뷰가 있습니다. 이 페이지를 떠나면 글이 사라집니다.</p>
          <div className="my-review__actions">
            <button ref={stayButtonRef} type="button" className="button" onClick={stayOnPage}>
              이 페이지에 남기
            </button>
            <button type="button" className="button button--secondary" onClick={followLeaveLink}>
              이동
            </button>
          </div>
        </div>
      ) : null}

      <div className="my-review" id="my-review">
        <span id="write-review" className="my-review__anchor" aria-hidden="true" />
        <h3>내 리뷰</h3>
        {mine.status === "anonymous" ? (
          <p className="my-review__login">
            리뷰를 쓰려면 로그인하세요. <Link href={loginHref(selfHref)}>로그인</Link>
          </p>
        ) : mine.status === "switched" ? (
          <p className="my-review__status" role="status">
            로그인한 사용자가 바뀌어 화면을 다시 불러오는 중입니다…
          </p>
        ) : mine.status === "unavailable" ? (
          <p className="my-review__status" role="alert">
            내 리뷰를 불러오지 못했습니다.{" "}
            <button type="button" className="link-button" onClick={handleReload} disabled={busy}>
              {pending === "reload" ? "불러오는 중…" : "다시 불러오기"}
            </button>
          </p>
        ) : (
          <>
            {sessionExpired ? (
              <p className="my-review__expired" role="alert">
                {REVIEW_SESSION_EXPIRED_MESSAGE}{" "}
                <Link href={loginHref(selfHref, { expired: true })}>다시 로그인</Link>
              </p>
            ) : null}

            {review !== null && !editing ? (
              <div className="my-review__view">
                <ReviewMeta createdAt={review.createdAt} updatedAt={review.updatedAt} edited={review.edited} />
                {review.containsSpoiler ? <p className="badge badge--warning">스포일러 포함</p> : null}
                <p className="review-body" dir="auto">
                  {review.body}
                </p>
                {confirmingDelete ? (
                  <div className="my-review__confirm" role="group" aria-label="리뷰 삭제 확인">
                    <p>리뷰를 삭제할까요? 삭제하면 되돌릴 수 없습니다.</p>
                    <div className="my-review__actions">
                      <button
                        ref={confirmRef}
                        type="button"
                        className="button button--danger"
                        onClick={handleDelete}
                        disabled={busy}
                      >
                        {pending === "delete" ? "삭제 중…" : "삭제"}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        onClick={() => {
                          setConfirmingDelete(false);
                          requestAnimationFrame(() => deleteButtonRef.current?.focus());
                        }}
                        disabled={busy}
                      >
                        취소
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="my-review__actions">
                    <button type="button" className="button" onClick={startEdit} disabled={busy}>
                      수정
                    </button>
                    <button
                      ref={deleteButtonRef}
                      type="button"
                      className="button button--secondary"
                      onClick={() => setConfirmingDelete(true)}
                      disabled={busy}
                    >
                      삭제
                    </button>
                  </div>
                )}
              </div>
            ) : !formOpen ? (
              <div className="my-review__empty">
                <p>아직 이 작품에 남긴 리뷰가 없어요. 다 본 작품이라면 감상을 남겨 주세요.</p>
                <button type="button" className="button" onClick={startCompose}>
                  리뷰 쓰기
                </button>
              </div>
            ) : (
              <form className="my-review__form" onSubmit={handleSubmit} aria-busy={busy} noValidate>
                <div className="field">
                  <label className="field__label" htmlFor={textareaId}>
                    리뷰 내용
                  </label>
                  <textarea
                    id={textareaId}
                    ref={textareaRef}
                    name="body"
                    className="review-textarea"
                    rows={6}
                    value={draft.body}
                    onChange={(event) => {
                      setDraft((current) => ({ ...current, body: event.target.value }));
                      setFieldErrors((current) => ({ ...current, body: null }));
                    }}
                    aria-invalid={fieldErrors.body ? true : undefined}
                    aria-describedby={describedBy}
                    autoComplete="off"
                    readOnly={pending === "save"}
                  />
                  <p id={hintId} className="field__hint">
                    제목 없이 일반 텍스트로 씁니다. 앞뒤 공백을 빼고 10자 이상 2,000자 이하(현재{" "}
                    {check.length.toLocaleString("ko-KR")}자 / {REVIEW_MAX_LENGTH.toLocaleString("ko-KR")}자)
                  </p>
                  {fieldErrors.body ? (
                    <p id={bodyErrorId} className="field__error">
                      {fieldErrors.body}
                    </p>
                  ) : null}
                </div>
                <div className="field field--checkbox">
                  <label>
                    <input
                      type="checkbox"
                      name="containsSpoiler"
                      checked={draft.containsSpoiler}
                      onChange={(event) => {
                        setDraft((current) => ({ ...current, containsSpoiler: event.target.checked }));
                        setFieldErrors((current) => ({ ...current, spoiler: null }));
                      }}
                      aria-invalid={fieldErrors.spoiler ? true : undefined}
                      aria-describedby={fieldErrors.spoiler ? spoilerErrorId : undefined}
                      disabled={pending === "save"}
                    />{" "}
                    스포일러 포함 (다른 사람에게는 기본으로 접혀 보입니다)
                  </label>
                  {fieldErrors.spoiler ? (
                    <p id={spoilerErrorId} className="field__error">
                      {fieldErrors.spoiler}
                    </p>
                  ) : null}
                </div>
                {fieldErrors.other ? (
                  <p className="field__error" role="alert">
                    {fieldErrors.other}
                  </p>
                ) : null}
                {confirmingDiscard ? (
                  <div className="my-review__confirm" role="group" aria-label="작성 중인 리뷰 버리기 확인">
                    <p>작성 중인 리뷰를 버릴까요? 버리면 되돌릴 수 없습니다.</p>
                    <div className="my-review__actions">
                      <button ref={discardRef} type="button" className="button button--danger" onClick={cancelEdit}>
                        버리기
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        onClick={() => {
                          setConfirmingDiscard(false);
                          requestAnimationFrame(() => textareaRef.current?.focus());
                        }}
                      >
                        계속 쓰기
                      </button>
                    </div>
                  </div>
                ) : null}
                <div className="my-review__actions">
                  <button type="submit" className="button" disabled={busy || !dirty || conflict !== null}>
                    {pending === "save" ? "저장 중…" : review === null ? "등록" : "수정 저장"}
                  </button>
                  <button type="button" className="button button--secondary" onClick={cancelEdit} disabled={busy}>
                    취소
                  </button>
                </div>
              </form>
            )}

            {conflict !== null ? (
              <div className="my-review__conflict" role="alert">
                <p>{CONFLICT_TEXT[conflict.reason]}</p>
                {conflict.latest === undefined ? (
                  <p>
                    최신 리뷰를 불러오지 못했습니다.{" "}
                    <button type="button" className="link-button" onClick={handleConflictReload} disabled={busy}>
                      {pending === "reload" ? "불러오는 중…" : "다시 불러오기"}
                    </button>
                  </p>
                ) : conflict.latest === null ? (
                  <>
                    <p>현재 저장된 리뷰가 없습니다.</p>
                    <div className="my-review__actions">
                      <button type="button" className="button" onClick={saveOverLatest} disabled={busy}>
                        {pending === "save" ? "저장 중…" : "작성 중인 글을 새 리뷰로 등록"}
                      </button>
                      <button type="button" className="button button--secondary" onClick={takeLatest} disabled={busy}>
                        작성 중인 글 버리기
                      </button>
                    </div>
                  </>
                ) : (
                  <>
                    <p className="my-review__latest-label">현재 저장된 최신 리뷰:</p>
                    <div className="my-review__latest">
                      <ReviewMeta
                        createdAt={conflict.latest.createdAt}
                        updatedAt={conflict.latest.updatedAt}
                        edited={conflict.latest.edited}
                      />
                      {conflict.latest.containsSpoiler ? <p className="badge badge--warning">스포일러 포함</p> : null}
                      <p className="review-body" dir="auto">
                        {conflict.latest.body}
                      </p>
                    </div>
                    <div className="my-review__actions">
                      <button type="button" className="button button--secondary" onClick={takeLatest} disabled={busy}>
                        최신 리뷰로 바꾸기(작성 중인 글 버림)
                      </button>
                      <button type="button" className="button" onClick={saveOverLatest} disabled={busy}>
                        {pending === "save" ? "저장 중…" : "작성 중인 글로 최신 리뷰 덮어쓰기"}
                      </button>
                    </div>
                  </>
                )}
              </div>
            ) : null}
          </>
        )}

        {notice ? (
          <p className={`review-notice review-notice--${notice.tone}`} role={notice.tone === "error" ? "alert" : "status"}>
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
      </div>

      <div className="review-list-area" aria-labelledby={listHeadingId} aria-busy={listLoading}>
        <h3 id={listHeadingId}>
          리뷰 목록
          {listState.status === "loaded" ? (
            <span className="review-list-area__count"> {listState.page.totalElements.toLocaleString("ko-KR")}개</span>
          ) : null}
        </h3>
        {listLoading ? (
          <p className="muted" role="status">
            리뷰 목록을 새로 불러오는 중…
          </p>
        ) : null}
        {listNotice ? (
          <p className="review-notice review-notice--error" role="alert">
            {listNotice}{" "}
            <button type="button" className="link-button" onClick={handleListReload} disabled={busy}>
              다시 시도
            </button>
          </p>
        ) : null}
        {listState.status === "unavailable" ? (
          <p className="review-notice review-notice--error" role="alert">
            리뷰 목록을 불러오지 못했습니다.{" "}
            <button type="button" className="link-button" onClick={handleListReload} disabled={busy}>
              다시 시도
            </button>
          </p>
        ) : (
          <PublicReviewList
            key={list.page}
            page={listState.page}
            reviewPage={list.page}
            hrefFor={hrefFor}
            myReviewId={myReviewId}
          />
        )}
      </div>
    </section>
  );
}
