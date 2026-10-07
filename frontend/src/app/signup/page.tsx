import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { SignupForm } from "@/components/auth/SignupForm";
import { loginHref, safeReturnTo } from "@/lib/auth/return-to";
import { getSession } from "@/lib/auth/session";

export const metadata: Metadata = {
  title: "회원가입",
};

export default async function SignupPage({ searchParams }: PageProps<"/signup">) {
  const { returnTo } = await searchParams;
  const target = safeReturnTo(returnTo);

  const session = await getSession();
  if (session.status === "authenticated") {
    redirect(target);
  }

  return (
    <section className="auth-page" aria-labelledby="signup-heading">
      <h1 id="signup-heading">회원가입</h1>
      <p className="auth-page__lead">가입하면 바로 로그인됩니다.</p>
      <SignupForm returnTo={target} />
      <p className="auth-page__switch">
        이미 계정이 있나요? <Link href={loginHref(target)}>로그인</Link>
      </p>
    </section>
  );
}
