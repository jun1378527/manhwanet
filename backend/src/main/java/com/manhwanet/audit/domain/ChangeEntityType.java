package com.manhwanet.audit.domain;

/** 관리 변경 기록의 대상 종류 (admin_change_log.entity_type) */
public enum ChangeEntityType {
    WORK, CREATOR, LISTING, PLATFORM, WORK_EXTERNAL_ID, CREATOR_EXTERNAL_ID, WORK_LINK, FIELD_SOURCE,
    COLLECTION_POLICY, CRAWL_RUN, STAGING_LISTING
}
