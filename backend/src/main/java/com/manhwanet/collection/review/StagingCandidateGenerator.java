package com.manhwanet.collection.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwanet.collection.domain.StagingCreditRow;
import com.manhwanet.collection.domain.StagingRow;
import com.manhwanet.platform.domain.ListingRepository;
import com.manhwanet.platform.domain.ListingRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 수집 대기 항목의 중복 후보를 만든다(#51, 설계 §9.2 중 최소 범위). 같은 입력으로 다시 돌려도 후보 행이 늘지 않는다.
 * <ul>
 *   <li>LISTING_KEY(100): 같은 (플랫폼, 외부 작품 ID)가 이미 작품에 연결됨 — 식별 키 충돌</li>
 *   <li>ALIAS_KEY(90): 이 항목의 별칭 ID 가 이미 작품에 연결됨</li>
 *   <li>TITLE_MATCH(40, 다른 수집 항목 30): 제목 키가 같음. <b>단독으로는 같은 작품이라는 뜻이 아니다</b></li>
 *   <li>CREATOR_OVERLAP(+40, 다른 수집 항목 +30): 제목 키가 같은 후보 중 작가 이름 키가 겹침</li>
 *   <li>경고: EDITION_SUFFIX·SEASON(어느 쪽 제목에든 표지), DIFFERENT_CREATORS(제목은 같고 작가가 겹치지 않음),
 *       AGE_CLASS_DIFF(일반·성인 등급이 엇갈림)</li>
 * </ul>
 * 이번 생성에 나오지 않은 열린 후보는 STALE 경고와 함께 REJECTED 로 닫는다. 그 자동 종료는 사람 판단이 아니다.
 * 같은 후보가 다시 생성되면 OPEN 으로 되돌리고 판단자·판단 시각을 비운다(#66).
 * 사람이 SAME_WORK(ACCEPTED)·DIFFERENT(REJECTED, STALE 없음)로 남긴 판단은 다시 열지 않는다.
 * 자동으로 연결·병합하지 않는다.
 * 전체 작품 중복 검수(작가만 겹치는 동명이인 탐지, 작품 간 후보, 색인)는 #41 범위다.
 */
@Component
public class StagingCandidateGenerator {

    static final String LISTING_KEY = "LISTING_KEY";
    static final String ALIAS_KEY = "ALIAS_KEY";
    static final String TITLE_MATCH = "TITLE_MATCH";
    static final String CREATOR_OVERLAP = "CREATOR_OVERLAP";
    static final String DIFFERENT_CREATORS = "DIFFERENT_CREATORS";
    static final String AGE_CLASS_DIFF = "AGE_CLASS_DIFF";
    static final String STALE = "STALE";

    private final StagingReviewRepository repository;
    private final ListingRepository listingRepository;
    private final ObjectMapper objectMapper;

    public StagingCandidateGenerator(StagingReviewRepository repository, ListingRepository listingRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.listingRepository = listingRepository;
        this.objectMapper = objectMapper;
    }

    private static final class Draft {
        double score;
        final Set<String> reasons = new LinkedHashSet<>();
        final Set<String> warnings = new TreeSet<>();

        void add(String reason, double points) {
            if (reasons.add(reason)) {
                score = Math.min(100, score + points);
            }
        }
    }

    /** 후보를 만들고(업서트) 이 항목의 전체 후보를 돌려준다. 호출자 트랜잭션 안에서 실행한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StagingReviewRepository.CandidateRow> generate(StagingRow staging, List<StagingCreditRow> credits,
            List<String> aliases, long actorId) {
        Map<Long, Draft> works = new LinkedHashMap<>();
        Map<Long, Draft> others = new LinkedHashMap<>();

        listingRepository.findByPlatformWorkId(staging.platformCode(), staging.platformWorkId())
                .ifPresent(l -> works.computeIfAbsent(l.workId(), k -> new Draft()).add(LISTING_KEY, 100));
        for (String alias : aliases) {
            listingRepository.findByPlatformWorkId(staging.platformCode(), alias)
                    .map(ListingRow::workId)
                    .ifPresent(workId -> works.computeIfAbsent(workId, k -> new Draft()).add(ALIAS_KEY, 90));
        }

        String title = staging.titleClean() != null ? staging.titleClean() : staging.titleRaw();
        String key = TitleKeys.key(title);
        Set<String> myMarkers = TitleKeys.markers(title);
        Set<String> myNames = names(credits.stream().map(StagingCreditRow::nameRaw).toList());
        if (key != null) {
            Map<Long, Set<String>> titleMarkers = new LinkedHashMap<>();
            for (StagingReviewRepository.TitleRow row : repository.workTitles()) {
                if (key.equals(TitleKeys.key(row.title()))) {
                    works.computeIfAbsent(row.id(), k -> new Draft()).add(TITLE_MATCH, 40);
                    titleMarkers.computeIfAbsent(row.id(), k -> new TreeSet<>()).addAll(TitleKeys.markers(row.title()));
                }
            }
            Map<Long, List<String>> creatorNames = repository.workCreatorNames(titleMarkers.keySet());
            Map<Long, List<String>> ages = repository.workAgeClasses(works.keySet());
            for (Map.Entry<Long, Draft> e : works.entrySet()) {
                Draft d = e.getValue();
                if (titleMarkers.containsKey(e.getKey())) {
                    d.warnings.addAll(myMarkers);
                    d.warnings.addAll(titleMarkers.get(e.getKey()));
                    creatorOverlap(d, myNames, names(creatorNames.getOrDefault(e.getKey(), List.of())), 40);
                }
                ageDiff(d, staging.ageClass(), ages.getOrDefault(e.getKey(), List.of()));
            }

            List<StagingReviewRepository.StagingTitleRow> matches = repository.otherStagingTitles(staging.id()).stream()
                    .filter(o -> key.equals(TitleKeys.key(o.titleClean() != null ? o.titleClean() : o.titleRaw())))
                    .toList();
            Map<Long, List<String>> otherNames = repository.stagingCreditNames(
                    matches.stream().map(StagingReviewRepository.StagingTitleRow::id).toList());
            for (StagingReviewRepository.StagingTitleRow o : matches) {
                Draft d = others.computeIfAbsent(o.id(), k -> new Draft());
                d.add(TITLE_MATCH, 30);
                d.warnings.addAll(myMarkers);
                d.warnings.addAll(TitleKeys.markers(o.titleClean() != null ? o.titleClean() : o.titleRaw()));
                creatorOverlap(d, myNames, names(otherNames.getOrDefault(o.id(), List.of())), 30);
                ageDiff(d, staging.ageClass(), List.of(o.ageClass()));
            }
        } else {
            Map<Long, List<String>> ages = repository.workAgeClasses(works.keySet());
            works.forEach((workId, d) -> ageDiff(d, staging.ageClass(), ages.getOrDefault(workId, List.of())));
        }

        Set<Long> produced = new HashSet<>();
        for (Map.Entry<Long, Draft> e : works.entrySet()) {
            produced.add(repository.upsertWorkCandidate(staging.id(), e.getKey(), e.getValue().score,
                    json(e.getValue().reasons), json(e.getValue().warnings)));
        }
        for (Map.Entry<Long, Draft> e : others.entrySet()) {
            produced.add(repository.upsertPairCandidate(staging.id(), e.getKey(), e.getValue().score,
                    json(e.getValue().reasons), json(e.getValue().warnings)));
        }
        for (StagingReviewRepository.CandidateRow row : repository.candidates(staging.id())) {
            if (row.status().equals("OPEN") && !produced.contains(row.id())) {
                repository.decideCandidate(row.id(), "REJECTED", actorId, STALE);
            }
        }
        return repository.candidates(staging.id());
    }

    private static void creatorOverlap(Draft d, Set<String> mine, Set<String> theirs, double points) {
        if (!mine.isEmpty() && !theirs.isEmpty()) {
            if (mine.stream().anyMatch(theirs::contains)) {
                d.add(CREATOR_OVERLAP, points);
            } else {
                d.warnings.add(DIFFERENT_CREATORS);
            }
        }
    }

    private static void ageDiff(Draft d, String mine, List<String> theirs) {
        if (("GENERAL".equals(mine) && theirs.contains("ADULT")) || ("ADULT".equals(mine) && theirs.contains("GENERAL"))) {
            d.warnings.add(AGE_CLASS_DIFF);
        }
    }

    private static Set<String> names(List<String> raw) {
        return raw.stream().map(TitleKeys::nameKey).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private String json(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
