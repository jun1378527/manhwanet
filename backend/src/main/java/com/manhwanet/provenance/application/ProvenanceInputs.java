package com.manhwanet.provenance.application;

import com.manhwanet.global.admin.AdminInputs;
import com.manhwanet.platform.domain.PlatformUrls;
import com.manhwanet.provenance.domain.ExternalIdSource;
import com.manhwanet.provenance.domain.ExternalIds;
import com.manhwanet.provenance.domain.LinkType;
import com.manhwanet.provenance.domain.SourceField;
import com.manhwanet.provenance.domain.SourceType;
import com.manhwanet.provenance.dto.EvidenceRequest;
import com.manhwanet.provenance.dto.ExternalIdInput;
import com.manhwanet.provenance.dto.LinkInput;
import com.manhwanet.work.domain.LanguageTags;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 외부 ID·공식 링크·근거 입력 검증 (#40). 오류는 AdminInputs 에 모아 한 번에 400 으로 돌려준다.
 * URL 은 형식만 검사하고 서버가 가져오지 않는다.
 */
public final class ProvenanceInputs {

    public static final int MAX_EVIDENCE = 50;
    public static final int MAX_EXTERNAL_IDS = 20;
    public static final int MAX_LINKS = 20;
    public static final int NOTE_MAX_LENGTH = 300;
    public static final int TARGET_REF_MAX_LENGTH = 200;
    public static final int SOURCE_REF_MAX_LENGTH = 500;

    /** 작품 핵심 정보 근거로 쓸 수 있는 필드(LISTING 은 연결 요청이나 근거 추가 API 로만) */
    public static final Set<SourceField> WORK_FIELDS = EnumSet.of(SourceField.TITLE, SourceField.CREDIT,
            SourceField.WORK_FORMAT, SourceField.ORIGIN_COUNTRY, SourceField.DESCRIPTION);

    private ProvenanceInputs() {
    }

    /**
     * 근거 목록. allowed 밖의 field 는 400. defaultField 가 있으면 field 생략 시 그 값을 쓴다.
     * listingIdAllowed 가 아니면 listingId 를 보낼 수 없다(연결 요청 안의 근거는 그 연결에 붙는다).
     */
    public static List<Evidence> evidence(List<EvidenceRequest> raw, String prefix, AdminInputs in, Clock clock,
            Set<SourceField> allowed, SourceField defaultField, boolean listingIdAllowed) {
        List<Evidence> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw.size() > MAX_EVIDENCE) {
            in.add(prefix, "근거는 " + MAX_EVIDENCE + "개 이하여야 합니다.");
            return result;
        }
        for (int i = 0; i < raw.size(); i++) {
            String p = prefix + "[" + i + "]";
            EvidenceRequest e = raw.get(i);
            if (e == null) {
                in.add(p, "근거 항목은 비어 있을 수 없습니다.");
                continue;
            }
            SourceField field = e.field() == null && defaultField != null
                    ? defaultField
                    : in.enumValue(e.field(), SourceField.class, p + ".field", true);
            if (field != null && !allowed.contains(field)) {
                in.add(p + ".field", "이 요청에서 쓸 수 있는 근거 필드: " + allowed);
                field = null;
            }
            Long listingId = in.integer(e.listingId(), p + ".listingId", 1, false);
            if (e.listingId() != null && !listingIdAllowed) {
                in.add(p + ".listingId", "이 요청의 근거는 대상 연결을 따로 지정하지 않습니다.");
            } else if (listingIdAllowed && field == SourceField.LISTING && e.listingId() == null) {
                in.add(p + ".listingId", "LISTING 근거에는 대상 연결(listingId)이 필요합니다.");
            } else if (listingIdAllowed && field != null && field != SourceField.LISTING && e.listingId() != null) {
                in.add(p + ".listingId", "listingId 는 LISTING 근거에만 지정합니다.");
            }
            String targetRef = in.text(e.targetRef(), p + ".targetRef", TARGET_REF_MAX_LENGTH, false);
            SourceType type = in.enumValue(e.sourceType(), SourceType.class, p + ".sourceType", true);
            String sourceRef = in.text(e.sourceRef(), p + ".sourceRef", SOURCE_REF_MAX_LENGTH, false);
            String sourceUrl = httpsUrl(e.sourceUrl(), p + ".sourceUrl", in);
            Instant verifiedAt = in.verifiedAt(e.verifiedAt(), p + ".verifiedAt", true, clock);
            String note = in.text(e.note(), p + ".note", NOTE_MAX_LENGTH, false);
            if (field == SourceField.DESCRIPTION && type != null && type != SourceType.STAFF
                    && type != SourceType.LICENSED) {
                in.add(p + ".sourceType", "줄거리 근거는 직접 작성(STAFF) 또는 허락받은 글(LICENSED)만 쓸 수 있습니다.");
            }
            if (type == SourceType.LICENSED && sourceRef == null && !in.hasError(p + ".sourceRef")) {
                in.add(p + ".sourceRef", "허락받은 글(LICENSED)에는 허락 근거(sourceRef)가 필요합니다.");
            } else if (type != null && type != SourceType.STAFF && sourceRef == null && sourceUrl == null
                    && !in.hasError(p + ".sourceRef") && !in.hasError(p + ".sourceUrl")) {
                in.add(p + ".sourceRef", "직접 작성(STAFF) 외의 근거에는 sourceRef 또는 sourceUrl 이 필요합니다.");
            }
            result.add(new Evidence(field, listingId, targetRef, type, sourceRef, sourceUrl, verifiedAt, note));
        }
        return result;
    }

    /** 바뀐 필드마다 근거가 하나 이상 있어야 한다 */
    public static void require(List<Evidence> evidence, SourceField field, String prefix, AdminInputs in) {
        if (evidence.stream().noneMatch(e -> e.field() == field)) {
            in.add(prefix, field + " 근거(evidence)가 하나 이상 필요합니다.");
        }
    }

    public static List<ExternalIdDraft> externalIds(List<ExternalIdInput> raw, String prefix, AdminInputs in,
            Clock clock) {
        List<ExternalIdDraft> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw.size() > MAX_EXTERNAL_IDS) {
            in.add(prefix, "외부 ID 는 " + MAX_EXTERNAL_IDS + "개 이하여야 합니다.");
            return result;
        }
        Set<List<String>> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String p = prefix + "[" + i + "]";
            ExternalIdDraft draft = externalId(raw.get(i), p, in, clock);
            if (draft != null && !seen.add(List.of(draft.source().name(), draft.externalId()))) {
                in.add(p + ".externalId", "같은 출처·ID 가 요청 안에서 중복되었습니다.");
            } else if (draft != null) {
                result.add(draft);
            }
        }
        return result;
    }

    /** 외부 ID 한 건. 필드 경로는 prefix + ".source" 등(prefix 가 빈 문자열이면 "source") */
    public static ExternalIdDraft externalId(ExternalIdInput input, String prefix, AdminInputs in, Clock clock) {
        String p = prefix.isEmpty() ? "" : prefix + ".";
        if (input == null) {
            in.add(prefix, "외부 ID 항목은 비어 있을 수 없습니다.");
            return null;
        }
        ExternalIdSource source = in.enumValue(input.source(), ExternalIdSource.class, p + "source", true);
        String id = in.text(input.externalId(), p + "externalId", ExternalIds.MAX_LENGTH, true);
        String normalized = null;
        if (source != null && id != null) {
            normalized = ExternalIds.normalize(source, id);
            if (normalized == null) {
                in.add(p + "externalId", source == ExternalIdSource.WIKIDATA
                        ? "Wikidata ID 는 Q 와 숫자(예: Q12345)여야 합니다."
                        : "외부 ID 는 공백 없는 영문·숫자·기호(ASCII) 1~100자여야 합니다.");
            }
        }
        String sourceUrl = httpsUrl(input.sourceUrl(), p + "sourceUrl", in);
        Instant verifiedAt = in.verifiedAt(input.verifiedAt(), p + "verifiedAt", true, clock);
        String note = in.text(input.note(), p + "note", NOTE_MAX_LENGTH, false);
        if (source == null || normalized == null || verifiedAt == null) {
            return null;
        }
        return new ExternalIdDraft(source, normalized, sourceUrl, verifiedAt, note);
    }

    public static List<LinkDraft> links(List<LinkInput> raw, String prefix, AdminInputs in, Clock clock) {
        List<LinkDraft> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw.size() > MAX_LINKS) {
            in.add(prefix, "공식 링크는 " + MAX_LINKS + "개 이하여야 합니다.");
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String p = prefix + "[" + i + "]";
            LinkDraft draft = link(raw.get(i), p, in, clock);
            if (draft != null && !seen.add(draft.url())) {
                in.add(p + ".url", "같은 URL 이 요청 안에서 중복되었습니다.");
            } else if (draft != null) {
                result.add(draft);
            }
        }
        return result;
    }

    public static LinkDraft link(LinkInput input, String prefix, AdminInputs in, Clock clock) {
        String p = prefix.isEmpty() ? "" : prefix + ".";
        if (input == null) {
            in.add(prefix, "링크 항목은 비어 있을 수 없습니다.");
            return null;
        }
        LinkType type = in.enumValue(input.linkType(), LinkType.class, p + "linkType", true);
        String url = null;
        if (input.url() == null || input.url().isBlank()) {
            in.add(p + "url", p + "url 은 필수입니다.");
        } else {
            url = httpsUrl(input.url(), p + "url", in);
        }
        String language = language(input.languageCode(), p + "languageCode", in);
        Instant verifiedAt = in.verifiedAt(input.verifiedAt(), p + "verifiedAt", true, clock);
        String note = in.text(input.note(), p + "note", NOTE_MAX_LENGTH, false);
        if (type == null || url == null || language == null || verifiedAt == null) {
            return null;
        }
        return new LinkDraft(type, url, language, verifiedAt, note, p + "url");
    }

    /** 선택 https URL: 비었으면 null, 형식이 틀리면 오류. 정규화한 값을 돌려준다(가져오지 않음) */
    public static String httpsUrl(String raw, String field, AdminInputs in) {
        if (raw == null || raw.isBlank()) {
            if (raw != null) {
                in.add(field, "URL 을 비워 둘 때는 값을 보내지 마세요(공백만 입력할 수 없습니다).");
            }
            return null;
        }
        PlatformUrls.Result result = PlatformUrls.validateGeneral(raw);
        if (!result.ok()) {
            in.add(field, result.reason());
            return null;
        }
        return result.normalizedUrl();
    }

    public static String language(String raw, String field, AdminInputs in) {
        if (raw == null || raw.isBlank()) {
            in.add(field, field + " 은 필수입니다.");
            return null;
        }
        try {
            return LanguageTags.normalize(raw);
        } catch (IllegalArgumentException e) {
            in.add(field, e.getMessage());
            return null;
        }
    }
}
