package com.manhwanet.collection.review;

import com.manhwanet.collection.application.CollectedListing;
import com.manhwanet.collection.application.CollectedListing.AgeSignal;
import com.manhwanet.collection.application.CollectedListing.CollectedCredit;
import com.manhwanet.collection.collector.adapter.DiscoveredRef;
import com.manhwanet.collection.collector.adapter.Discovery;
import com.manhwanet.collection.collector.adapter.PlatformAdapter;
import com.manhwanet.collection.collector.adapter.TaskRequest;
import com.manhwanet.collection.collector.core.FetchContext;
import com.manhwanet.collection.collector.core.FetchResponse;
import com.manhwanet.collection.domain.RunKind;
import com.manhwanet.collection.domain.TaskKind;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시연용 모의 어댑터(테스트 소스 전용). TOOMICS 코드를 빌리지만 호스트는 실제로 해석되지 않는 toomics.mock.test 이고,
 * 로컬 모의 HTTP 서버(127.0.0.1)가 돌려주는 가상 작품 페이지만 읽는다. 상세 본문 형식: {@code key=value} 줄(title·age·story·art).
 */
final class DemoToomicsAdapter implements PlatformAdapter {

    static final String BASE = "https://" + ReviewFixtures.TOOMICS_HOST;

    @Override
    public String platformCode() {
        return "TOOMICS";
    }

    @Override
    public String version() {
        return "demo-1";
    }

    @Override
    public List<TaskRequest> discoverySources(RunKind kind) {
        return List.of(new TaskRequest(TaskKind.SITEMAP, BASE + "/demo-sitemap.xml"));
    }

    @Override
    public Discovery discover(TaskKind kind, String url, FetchContext context) {
        List<DiscoveredRef> refs = new ArrayList<>();
        for (String line : context.get(url).text().split("\n")) {
            if (line.startsWith("detail ")) {
                refs.add(DiscoveredRef.detail(line.substring(7).strip()));
            }
        }
        return new Discovery(List.of(), refs);
    }

    @Override
    public CollectedListing fetchDetail(String url, FetchContext context) {
        FetchResponse response = context.get(url);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : response.text().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        List<CollectedCredit> credits = new ArrayList<>();
        if (fields.containsKey("story")) {
            credits.add(new CollectedCredit(fields.get("story"), "글", "STORY", null));
        }
        if (fields.containsKey("art")) {
            credits.add(new CollectedCredit(fields.get("art"), "그림", "ART", null));
        }
        String age = fields.get("age");
        String id = normalizeId(url);
        return new CollectedListing("TOOMICS", id, BASE + "/demo/" + id, "OK", "SERIES", "ko", fields.get("title"),
                fields.get("title"), List.of("판타지"), age, age == null ? List.of() : List.of(new AgeSignal("detail:age", age, true)),
                "연재중", "ONGOING", null, null, Map.of("demo", true), Map.of("titleRaw", Map.of("path", "title=")), credits,
                "demo-sitemap", List.of());
    }

    @Override
    public String normalizeId(String rawUrlOrId) {
        String path = rawUrlOrId.contains("://") ? URI.create(rawUrlOrId).getPath() : rawUrlOrId;
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
