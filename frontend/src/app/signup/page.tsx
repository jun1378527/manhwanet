import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { AuthShell } from "@/components/auth/AuthShell";
import { SignupForm } from "@/components/auth/SignupForm";
import { safeReturnTo } from "@/lib/auth/return-to";
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
    <AuthShell mode="signup" returnTo={target} title="회원가입" lead="가입하면 바로 로그인돼요.">
      <SignupForm returnTo={target} />
    </AuthShell>
  );
}
