package com.manhwanet.collection.collector;

import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.collector.adapter.DiscoveredRef;
import com.manhwanet.collection.collector.adapter.Discovery;
import com.manhwanet.collection.collector.adapter.PlatformAdapter;
import com.manhwanet.collection.collector.adapter.TaskRequest;
import com.manhwanet.collection.collector.core.FetchContext;
import com.manhwanet.collection.collector.core.FetchResponse;
import com.manhwanet.collection.collector.core.ParseFailureException;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.TaskError;
import com.manhwanet.collection.domain.TaskKind;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 테스트 어댑터(RIDI 코드, 호스트 ridi.mock.test). 목록 본문 줄 형식:
 * {@code detail <url>}, {@code adult <id>}, {@code sitemap <url>}, {@code list <url>}. 상세 본문에 PARSE_ERROR 가 있으면 파싱 실패.
 */
public final class FakeAdapter implements PlatformAdapter {

    public static final String HOST = "ridi.mock.test";
    public static final String BASE = "https://" + HOST;

    public final List<TaskRequest> seeds = new CopyOnWriteArrayList<>();
    /** 상세 요청 중에 실행할 동작(예: 결과를 무시하고 예외 삼키기) */
    public volatile Consumer<FetchContext> duringDetail;
    public volatile boolean swallowHalts;

    public void reset() {
        seeds.clear();
        seeds.add(new TaskRequest(TaskKind.SITEMAP, BASE + "/sitemap.xml"));
        duringDetail = null;
        swallowHalts = false;
    }

    @Override
    public String platformCode() {
        return "RIDI";
    }

    @Override
    public String version() {
        return "fake-1";
    }

    @Override
    public List<TaskRequest> discoverySources(RunKind kind) {
        return List.copyOf(seeds);
    }

    @Override
    public Discovery discover(TaskKind kind, String url, FetchContext context) {
        FetchResponse response = context.get(url);
        List<TaskRequest> next = new ArrayList<>();
        List<DiscoveredRef> refs = new ArrayList<>();
        for (String line : response.text().split("\n")) {
            String[] parts = line.strip().split(" ", 2);
            if (parts.length < 2) {
                continue;
            }
            switch (parts[0]) {
                case "detail" -> refs.add(DiscoveredRef.detail(parts[1]));
                case "adult" -> refs.add(new DiscoveredRef(null, listing(parts[1], "SKIPPED_ADULT", "성인작 " + parts[1],
                        List.of(new AgeSignal("list:label", "19", true)))));
                case "sitemap" -> next.add(new TaskRequest(TaskKind.SITEMAP, parts[1]));
                case "list" -> next.add(new TaskRequest(TaskKind.LIST, parts[1]));
                default -> {
                }
            }
        }
        return new Discovery(next, refs);
    }

    @Override
    public CollectedListing fetchDetail(String url, FetchContext context) {
        if (duringDetail != null) {
            duringDetail.accept(context);
        }
        FetchResponse response;
        try {
            response = context.get(url);
        } catch (RuntimeException e) {
            if (swallowHalts) {
                // 잘못 만든 어댑터: 예외를 삼키고 결과를 돌려준다(수집기가 막아야 함)
                return listing(normalizeId(url), "OK", "삼킨 결과", List.of());
            }
            throw e;
        }
        String body = response.text();
        if (body.contains("PARSE_ERROR")) {
            throw new ParseFailureException("상세 형식이 다릅니다", response.body());
        }
        String title = body.strip().startsWith("title=") ? body.strip().substring(6) : "제목 " + normalizeId(url);
        return listing(normalizeId(url), "OK", title, List.of(new AgeSignal("detail:age", "15세 이용가", true)));
    }

    @Override
    public String normalizeId(String rawUrlOrId) {
        String path = rawUrlOrId.contains("://") ? URI.create(rawUrlOrId).getPath() : rawUrlOrId;
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public Optional<CollectedListing> failedDetail(String url, TaskError error) {
        String status = switch (error) {
            case GATED -> "GATED";
            case NOT_FOUND -> "NOT_FOUND";
            default -> "PARSE_FAILED";
        };
        return Optional.of(listing(normalizeId(url), status, "실패 " + normalizeId(url), List.of()));
    }

    static CollectedListing listing(String id, String status, String title, List<AgeSignal> signals) {
        return new CollectedListing("RIDI", id, BASE + "/books/" + id, status, "SERIES", "ko", title, title, List.of("드라마"),
                null, signals, null, null, null, null, Map.of(), Map.of(), List.of(), "sitemap", List.of());
    }
}
