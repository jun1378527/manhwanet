#!/bin/sh
# 컨테이너 최초 생성(빈 볼륨) 시 한 번만 실행된다.
# Backend 통합 테스트(test 프로필)가 사용하는 테스트 전용 DB를 만든다.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -c "CREATE DATABASE \"${TEST_DB_NAME:-manhwanet_test}\";"
