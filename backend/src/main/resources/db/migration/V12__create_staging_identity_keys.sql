-- #61: 대표 ID 와 별칭은 플랫폼마다 하나의 식별 공간이다.
-- V10 은 이미 적용되어 고치지 않는다. 같은 external_id 는 대표이든 별칭이든 한 행만 가리킨다.
-- 새 FK 에 CASCADE 없음.

CREATE TABLE staging_identity_keys (
    platform_code VARCHAR(30)  NOT NULL,
    external_id   VARCHAR(100) NOT NULL,
    staging_id    BIGINT       NOT NULL,
    key_role      VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_staging_identity_keys PRIMARY KEY (platform_code, external_id),
    CONSTRAINT fk_staging_identity_platform FOREIGN KEY (platform_code) REFERENCES platforms (code),
    CONSTRAINT fk_staging_identity_staging FOREIGN KEY (staging_id) REFERENCES staging_listings (id),
    CONSTRAINT ck_staging_identity_id CHECK (external_id ~ '^[!-~]+$'),
    CONSTRAINT ck_staging_identity_role CHECK (key_role IN ('CANONICAL', 'ALIAS'))
);
-- 한 대기 행의 대표 키는 하나
CREATE UNIQUE INDEX uk_staging_identity_canonical ON staging_identity_keys (staging_id) WHERE key_role = 'CANONICAL';
CREATE INDEX idx_staging_identity_staging ON staging_identity_keys (staging_id);

-- 이미 있는 행. 대표 ID 와 겹치는 별칭은 넣지 않는다(교차 연결을 식별 공간으로 확정하지 않음).
INSERT INTO staging_identity_keys (platform_code, external_id, staging_id, key_role)
SELECT platform_code, platform_work_id, id, 'CANONICAL' FROM staging_listings;

INSERT INTO staging_identity_keys (platform_code, external_id, staging_id, key_role)
SELECT a.platform_code, a.alias_id, a.staging_id, 'ALIAS'
FROM staging_id_aliases a
WHERE NOT EXISTS (
    SELECT 1 FROM staging_identity_keys k
    WHERE k.platform_code = a.platform_code AND k.external_id = a.alias_id
);
