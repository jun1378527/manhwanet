#!/bin/sh
# 컨테이너 최초 생성(빈 볼륨) 시 한 번만 실행된다.
# 포트폴리오 데모(#67)가 쓰는 데모 전용 DB를 만든다. 개발·테스트 DB와 분리하며, 이름은 '_demo' 로 끝나야 한다.
# 이미 있는 볼륨에는 실행되지 않으므로 scripts/demo/create-demo-db.ps1 로 만든다.
set -e

DEMO_DB="${DEMO_DB_NAME:-manhwanet_demo}"
case "$DEMO_DB" in
  *[!a-z0-9_]* | "")
    echo "DEMO_DB_NAME '$DEMO_DB' 은 소문자·숫자·'_' 만 쓸 수 있어 데모 DB를 만들지 않았습니다." >&2
    exit 0 ;;
  *_demo) ;;
  *)
    echo "DEMO_DB_NAME '$DEMO_DB' 이 '_demo' 로 끝나지 않아 데모 DB를 만들지 않았습니다." >&2
    exit 0 ;;
esac
if [ "$DEMO_DB" = "$POSTGRES_DB" ] || [ "$DEMO_DB" = "${TEST_DB_NAME:-manhwanet_test}" ]; then
  echo "DEMO_DB_NAME 이 개발·테스트 DB 이름과 같아 데모 DB를 만들지 않았습니다." >&2
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -c "CREATE DATABASE \"$DEMO_DB\";"
