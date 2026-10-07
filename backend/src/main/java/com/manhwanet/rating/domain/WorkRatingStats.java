package com.manhwanet.rating.domain;

/** 작품별 집계 한 행 (COUNT, SUM 을 같은 SQL에서 읽음) */
public interface WorkRatingStats {

    Long getWorkId();

    Long getRatingCount();

    Long getScoreHalfSum();
}
