package com.manhwanet.provenance.domain;

import com.manhwanet.audit.domain.ChangeEntityType;

/** 외부 ID 를 가진 대상. 표·열 이름은 고정 값이라 SQL 에 그대로 넣는다 */
public enum ExternalIdOwner {
    WORK("work_external_ids", "work_id", ChangeEntityType.WORK_EXTERNAL_ID),
    CREATOR("creator_external_ids", "creator_id", ChangeEntityType.CREATOR_EXTERNAL_ID);

    private final String table;
    private final String ownerColumn;
    private final ChangeEntityType entityType;

    ExternalIdOwner(String table, String ownerColumn, ChangeEntityType entityType) {
        this.table = table;
        this.ownerColumn = ownerColumn;
        this.entityType = entityType;
    }

    public String table() {
        return table;
    }

    public String ownerColumn() {
        return ownerColumn;
    }

    public ChangeEntityType entityType() {
        return entityType;
    }
}
