import Link from "next/link";

import { StatusMessage } from "@/components/StatusMessage";

export default function NotFound() {
  return (
    <StatusMessage title="페이지를 찾을 수 없습니다">
      <p>주소가 바뀌었거나 없는 페이지입니다.</p>
      <Link className="button" href="/works">
        작품 목록으로
      </Link>
    </StatusMessage>
  );
}
