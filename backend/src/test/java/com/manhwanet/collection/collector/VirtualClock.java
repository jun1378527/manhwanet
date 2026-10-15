package com.manhwanet.collection.collector;

import com.manhwanet.collection.collector.core.CollectorClock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/** 테스트용 가상 시계. sleep 은 시간을 바로 앞으로 돌리고 등록된 동작(대기 중 사건)을 실행한다 */
public final class VirtualClock implements CollectorClock {

    private volatile long nanos = TimeUnit.DAYS.toNanos(1);
    private final List<LongConsumer> afterSleep = new CopyOnWriteArrayList<>();
    private volatile long sleptMillis;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public void sleep(long millis) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        sleptMillis += millis;
        for (LongConsumer hook : afterSleep) {
            hook.accept(millis);
        }
    }

    public long millis() {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    public long sleptMillis() {
        return sleptMillis;
    }

    public void advance(long millis) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }

    public void onSleep(LongConsumer hook) {
        afterSleep.add(hook);
    }

    public void reset() {
        afterSleep.clear();
        sleptMillis = 0;
    }
}
