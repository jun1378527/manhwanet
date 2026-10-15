package com.manhwanet.collection.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** #46 플랫폼별 하루 요청 수 (V13 collector_daily_requests). 날짜는 DB now() 의 한국 시간 날짜 */
@Repository
public class CollectorRequestCounterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CollectorRequestCounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 오늘 요청 수를 하나 올린다. 이미 상한(cap)에 닿았으면 올리지 않고 false. 한 문장(행 잠금)이라 동시에 불러도 상한을 넘지 않는다.
     * 수집기는 트랜잭션 밖에서 부르므로 바로 커밋되고 연결을 돌려준다.
     */
    public boolean tryIncrement(String platformCode, int cap) {
        if (cap < 1) {
            return false;
        }
        return !jdbc.queryForList("""
                INSERT INTO collector_daily_requests (platform_code, request_date, request_count)
                VALUES (:platform, CAST(now() AT TIME ZONE 'Asia/Seoul' AS DATE), 1)
                ON CONFLICT (platform_code, request_date) DO UPDATE
                    SET request_count = collector_daily_requests.request_count + 1, updated_at = now()
                    WHERE collector_daily_requests.request_count < :cap
                RETURNING request_count""",
                new MapSqlParameterSource().addValue("platform", platformCode).addValue("cap", cap), Integer.class).isEmpty();
    }

    public int today(String platformCode) {
        return jdbc.queryForList("""
                SELECT request_count FROM collector_daily_requests
                WHERE platform_code = :platform AND request_date = CAST(now() AT TIME ZONE 'Asia/Seoul' AS DATE)""",
                new MapSqlParameterSource("platform", platformCode), Integer.class).stream().findFirst().orElse(0);
    }
}
