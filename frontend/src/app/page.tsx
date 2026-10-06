import Link from "next/link";

export default function Home() {
  return (
    <section className="home">
      <h1>만화넷</h1>
      <p>한국 웹툰과 일본 만화를 한곳에서 찾아보세요.</p>
      <Link className="button" href="/works">
        작품 목록 보기
      </Link>
    </section>
  );
}
