import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { AuthShell } from "@/components/auth/AuthShell";
import { LoginForm } from "@/components/auth/LoginForm";
import { safeReturnTo } from "@/lib/auth/return-to";
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
    <AuthShell
      mode="login"
      returnTo={target}
      title="로그인"
      lead="별점과 리뷰를 남기려면 로그인하세요."
      notice={
        reason === "expired" ? (
          <p className="auth-card__notice" role="status">
            세션이 만료되었습니다. 다시 로그인해 주세요.
          </p>
        ) : null
      }
    >
      <LoginForm returnTo={target} />
    </AuthShell>
  );
}
