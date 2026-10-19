"use client";

import Link from "next/link";

import { useSessionChrome } from "@/components/auth/SessionProvider";

/** 푸터의 계정 안내. 헤더와 같은 클라이언트 상태를 쓴다. */
export function FooterAccount() {
  const { session } = useSessionChrome();

  if (session.status === "authenticated") {
    return (
      <p>
        <span dir="auto" className="site-footer__nickname">
          {session.user.nickname}
        </span>{" "}
        님으로 로그인되어 있습니다. 로그아웃은 화면 위쪽에서 할 수 있어요.
      </p>
    );
  }

  if (session.status === "unavailable") {
    return <p>로그인 상태를 확인하지 못했습니다. 화면 위쪽에서 다시 확인할 수 있어요.</p>;
  }

  return (
    <>
      <p>별점과 리뷰는 로그인한 뒤 남길 수 있어요. 비밀번호는 15자 이상입니다.</p>
      <ul className="site-footer__links">
        <li>
          <Link href="/login">로그인</Link>
        </li>
        <li>
          <Link href="/signup">회원가입</Link>
        </li>
      </ul>
    </>
  );
}
