import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { ApiUnavailable } from "@/components/StatusMessage";
import { WorksExplorer } from "@/components/works/WorksExplorer";
import { WorksResults } from "@/components/works/WorksResults";
import { getWorkFilterOptions, searchWorks } from "@/lib/api/works";
import { parseWorksQuery, worksSearchHref } from "@/lib/works/search";

export const metadata: Metadata = {
  title: "작품 찾기",
};

/**
 * 작품 찾기(/works). 검색·필터·정렬·페이지 상태는 URL 에 있고, 서버가 그 주소로 전체 공개 작품에서 결과를 만든다.
 * - 잘못된 값·중복 값·앞뒤 공백이 있는 검색어는 고친 정규 주소로 보낸다(307). 기본값만 적은 주소는 그대로 둔다.
 * - 선택지에 없는 플랫폼(공개 작품 연결이 없는 코드)은 뺀 주소로 보낸다.
 */
export default async function WorksPage({ searchParams }: PageProps<"/works">) {
  const { query, changed } = parseWorksQuery(await searchParams);
  if (changed) {
    redirect(worksSearchHref(query));
  }

  const [result, filters] = await Promise.all([searchWorks(query), getWorkFilterOptions()]);
  const platforms = filters.ok ? filters.data.platforms.map(({ code, name }) => ({ code, name })) : null;
  if (platforms && query.platform && !platforms.some((p) => p.code === query.platform)) {
    redirect(worksSearchHref({ ...query, platform: null, page: 1 }));
  }

  return (
    <div className="works-page">
      <header className="works-head">
        <h1 className="works-head__title">작품 찾기</h1>
        <p className="works-head__lead">웹툰과 만화를 한 목록에서 찾아보고, 독자 별점과 리뷰를 확인하세요.</p>
      </header>
      <WorksExplorer query={query} platforms={platforms}>
        {result.ok ? <WorksResults query={query} data={result.data} /> : <ApiUnavailable retryHref={worksSearchHref(query)} />}
      </WorksExplorer>
    </div>
  );
}
