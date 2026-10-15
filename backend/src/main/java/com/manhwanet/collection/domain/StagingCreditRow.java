package com.manhwanet.collection.domain;

public record StagingCreditRow(long id, String nameRaw, String roleRaw, CreditRole role, String platformCreatorId,
        int position) {
}
