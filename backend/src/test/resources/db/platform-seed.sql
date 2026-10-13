-- 테스트 DB 정리용 플랫폼 초기 데이터. V7__create_platforms_and_work_listings.sql 의 초기 데이터와 같아야 한다
-- (PlatformListingMigrationTest.cleanerSeedMatchesMigrationSeed 가 새 스키마에 마이그레이션한 결과와 비교해 확인한다).
INSERT INTO platforms (code, name, work_url_pattern, url_rule_basis, url_rule_checked_on) VALUES
    ('NAVER_WEBTOON', '네이버웹툰', '^https://comic\.naver\.com/webtoon/list\?titleId=(?<id>[0-9]+)$', 'OBSERVED', DATE '2026-10-12'),
    ('KAKAO_PAGE', '카카오페이지', '^https://page\.kakao\.com/content/(?<id>[0-9]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('KAKAO_WEBTOON', '카카오웹툰', '^https://webtoon\.kakao\.com/content/[^/?#]+/(?<id>[0-9]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('LEZHIN', '레진코믹스', '^https://www\.lezhin\.com/ko/comic/(?<id>[A-Za-z0-9_-]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('RIDI', '리디', '^https://ridibooks\.com/books/(?<id>[0-9]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('BOMTOON', '봄툰', '^https://www\.bomtoon\.com/detail/(?<id>[^/?#]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('TOPTOON', '탑툰', '^https://toptoon\.com/(comic|complete)/ep_list/(?<id>[A-Za-z0-9_-]+)$', 'OBSERVED', DATE '2026-10-11'),
    ('TOOMICS', '투믹스', NULL, 'UNVERIFIED', NULL),
    ('COMICO_KR', '코미코', NULL, 'UNVERIFIED', NULL),
    ('MRBLUE', '미스터블루', NULL, 'UNVERIFIED', NULL);

INSERT INTO platform_hosts (platform_code, host) VALUES
    ('NAVER_WEBTOON', 'comic.naver.com'),
    ('KAKAO_PAGE', 'page.kakao.com'),
    ('KAKAO_WEBTOON', 'webtoon.kakao.com'),
    ('LEZHIN', 'www.lezhin.com'),
    ('RIDI', 'ridibooks.com'),
    ('BOMTOON', 'www.bomtoon.com'),
    ('TOPTOON', 'toptoon.com'),
    ('TOOMICS', 'toomics.com'),
    ('COMICO_KR', 'www.comico.kr'),
    ('COMICO_KR', 'comico.kr'),
    ('MRBLUE', 'www.mrblue.com'),
    ('MRBLUE', 'm.mrblue.com');
