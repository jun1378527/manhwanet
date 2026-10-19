"use client";

import { type Ref } from "react";

import type { FeedbackTone } from "@/lib/auth/messages";

type Props = {
  tone: FeedbackTone;
  message: string;
  /** wait 일 때 다시 시도할 수 있는 대략의 시각(예: "오후 3:15"). 자동 재전송은 하지 않는다 */
  retryAt?: string | null;
  alertRef?: Ref<HTMLDivElement>;
};

const TITLES: Record<FeedbackTone, string> = {
  error: "다시 확인해 주세요",
  wait: "잠시 후 다시 시도해 주세요",
  offline: "서버에 연결하지 못했습니다",
};

/**
 * 폼 전체 결과 안내. role="alert" 로 바로 읽히고, 입력 칸에 연결할 오류가 없을 때는 폼이 이 상자로 포커스를 옮긴다(tabIndex -1).
 * 401(거절)·429/503 AUTH_BUSY(대기)·연결 실패를 색과 제목으로 구분한다.
 */
export function AuthAlert({ tone, message, retryAt, alertRef }: Props) {
  return (
    <div ref={alertRef} className={`form-alert form-alert--${tone}`} role="alert" tabIndex={-1}>
      <p className="form-alert__title">{TITLES[tone]}</p>
      <p className="form-alert__message">{message}</p>
      {tone === "wait" && retryAt ? <p className="form-alert__meta">{retryAt}쯤부터 다시 시도할 수 있습니다.</p> : null}
      {tone === "wait" ? <p className="form-alert__meta">자동으로 다시 보내지 않습니다. 시간이 지난 뒤 직접 눌러 주세요.</p> : null}
      {tone === "offline" ? (
        <p className="form-alert__meta">입력한 내용은 그대로 두었습니다. 자동으로 다시 보내지 않으니 잠시 후 직접 눌러 주세요.</p>
      ) : null}
    </div>
  );
}
