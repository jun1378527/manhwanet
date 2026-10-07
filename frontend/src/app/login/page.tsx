import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { LoginForm } from "@/components/auth/LoginForm";
import { safeReturnTo, signupHref } from "@/lib/auth/return-to";
import { getSession } from "@/lib/auth/session";

export const metadata: Metadata = {
  title: "로그인",
};

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const { returnTo, reason } = await searchParams;
  const target = safeReturnTo(returnTo);

  const session = await getSession();
  if (session.status === "authenticated") {
    redirect(target);
  }

  return (
    <section className="auth-page" aria-labelledby="login-heading">
      <h1 id="login-heading">로그인</h1>
      {reason === "expired" ? (
        <p className="auth-page__notice" role="status">
          세션이 만료되었습니다. 다시 로그인해 주세요.
        </p>
      ) : null}
      <LoginForm returnTo={target} />
      <p className="auth-page__switch">
        아직 계정이 없나요? <Link href={signupHref(target)}>회원가입</Link>
      </p>
    </section>
  );
}
