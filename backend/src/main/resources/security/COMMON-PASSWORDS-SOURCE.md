# common-passwords.txt 출처

- 원본: SecLists `Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt`
  - 저장소: https://github.com/danielmiessler/SecLists (커밋 49c3b2d1d2481572bd7b0cb5af875a73cdf9d08e)
  - 원본 SHA-256: 424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51 (1,000,000줄)
  - SecLists 라이선스: MIT (Copyright (c) 2018 Daniel Miessler)
  - 원 데이터: Mark Burnett가 2015-02-09 공개한 "ten million passwords" 데이터셋. 작성자가 퍼블릭 도메인으로 공개한다고 밝힘
    (https://medium.com/xato-security/today-i-am-releasing-ten-million-passwords-b6278bbe7495)
- 가공: 각 줄을 NFKC 정규화 → Locale.ROOT 소문자 변환 → 15~128 코드 포인트만 남김 → 중복 제거 → 정렬 (10,898개)
  - 15자 미만은 최소 길이 정책에서 이미 거부되므로 포함하지 않는다.
