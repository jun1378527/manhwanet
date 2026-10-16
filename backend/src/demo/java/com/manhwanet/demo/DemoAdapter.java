package com.manhwanet.demo;

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
 * 데모 수집 어댑터(#67). 플랫폼 코드는 외래 키 때문에 TOOMICS 를 빌리지만, 주소는 실제로 해석되지 않는 데모 호스트이고
 * 요청은 {@link DemoMockSite}(127.0.0.1)로만 간다. 실제 플랫폼 어댑터가 아니다.
 */
final class DemoAdapter implements PlatformAdapter {

    static final String PLATFORM = "TOOMICS";
    static final String HOST = "toomics.demo.mock.test";
    static final String BASE = "https://" + HOST;

    @Override
    public String platformCode() {
        return PLATFORM;
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
        return new CollectedListing(PLATFORM, id, BASE + "/demo/" + id, "OK", "SERIES", "ko", fields.get("title"),
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
