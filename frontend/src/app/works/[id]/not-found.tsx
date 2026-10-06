import Link from "next/link";

import { StatusMessage } from "@/components/StatusMessage";

export default function WorkNotFound() {
  return (
    <StatusMessage title="작품을 찾을 수 없습니다">
      <p>존재하지 않거나 주소가 잘못된 작품입니다.</p>
      <Link className="button" href="/works">
        작품 목록으로
      </Link>
    </StatusMessage>
  );
}
