package com.manhwanet.collection.collector.adapter;

import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.collector.core.FetchContext;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.TaskError;
import com.manhwanet.collection.domain.TaskKind;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 플랫폼 어댑터 계약(설계 §5.2를 작업 큐 단위로 옮김). 실제 플랫폼 어댑터는 후속 이슈(#47~#50)에서 만든다.
 * <ul>
 *   <li>어댑터는 HTTP 를 직접 호출하지 않고 {@link FetchContext#get} 으로만 요청한다.</li>
 *   <li>{@code FetchContext} 가 던지는 예외(작업 실패·실행 정지)를 잡지 않는다.</li>
 *   <li>파싱하지 못하면 {@link com.manhwanet.collection.collector.core.ParseFailureException} 을 던진다(원문은 표본으로 남김).</li>
 *   <li>PROBE 실행에서는 SITEMAP 작업만 실행하고, 작품 참조·상세·staging 반영은 하지 않는다(수집기가 막음).</li>
 * </ul>
 */
public interface PlatformAdapter {

    /** platforms.code */
    String platformCode();

    /** crawl_runs.adapter_version (공백 없는 ASCII 1~30자) */
    String version();

    /** 실행 종류별 시작 작업(사이트맵·목록 등 발견 경로) */
    List<TaskRequest> discoverySources(RunKind kind);

    /** SITEMAP·LIST 작업 */
    Discovery discover(TaskKind kind, String url, FetchContext context);

    /** DETAIL 작업. detailStatus 는 OK 또는 어댑터가 화면으로 판단한 GATED */
    CollectedListing fetchDetail(String url, FetchContext context);

    /** 정규 작품 ID(#39 url_pattern 과 같은 규칙) */
    String normalizeId(String rawUrlOrId);

    /** 상세가 GATED·NOT_FOUND·PARSE_FAILED 등으로 끝났을 때 staging 에 남길 실패 기록(선택) */
    default Optional<CollectedListing> failedDetail(String url, TaskError error) {
        return Optional.empty();
    }

    /** 공통 규칙에 더하는 플랫폼별 로그인·성인 인증·결제 리다이렉트 판단 */
    default boolean isGateRedirect(URI target) {
        return false;
    }
}
