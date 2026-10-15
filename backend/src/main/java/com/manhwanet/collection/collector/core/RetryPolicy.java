package com.manhwanet.collection.collector.core;

import java.util.OptionalInt;
import java.util.random.RandomGenerator;

/**
 * 타임아웃·연결 오류·5xx 재시도(설계 §7.3): 30초, 2분, 8분, 30분 + 무작위 지연(각 기본값의 0~10%), 최대 4회.
 * 처음 시도까지 합쳐 5회이며 #45 {@code CrawlTaskService.MAX_ATTEMPTS} 와 같다.
 */
public final class RetryPolicy {

    static final int[] BACKOFF_SECONDS = {30, 120, 480, 1800};
    public static final int MAX_RETRIES = BACKOFF_SECONDS.length;

    private RetryPolicy() {
    }

    /**
     * @param attempts 방금 끝난 시도까지의 횟수(1부터)
     * @return 다시 시도할 때까지 기다릴 초. 더 시도하지 않으면 빈 값
     */
    public static OptionalInt retryDelaySeconds(int attempts, RandomGenerator random) {
        if (attempts < 1 || attempts > MAX_RETRIES) {
            return OptionalInt.empty();
        }
        int base = BACKOFF_SECONDS[attempts - 1];
        return OptionalInt.of(base + random.nextInt(base / 10 + 1));
    }
}
