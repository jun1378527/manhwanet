<#
.SYNOPSIS
  데모 전용 DB 를 지우고 빈 DB 로 다시 만듭니다 (#67). 데모 DB 만 대상입니다.

.DESCRIPTION
  - 대상은 DEMO_DB_NAME(기본 manhwanet_demo) 하나뿐입니다. 이름이 '_demo' 로 끝나지 않거나
    DB_NAME·TEST_DB_NAME 과 같으면 거절합니다.
  - 확인 없이 지우지 않습니다. -ConfirmDatabase 에 데모 DB 이름을 정확히 넘기거나,
    실행 중 물을 때 이름을 직접 입력해야 합니다.
  - 데모 웹 서버·데모 명령을 먼저 끄세요(남은 접속은 강제로 끊습니다).

.EXAMPLE
  .\scripts\demo\reset-demo-db.ps1
  .\scripts\demo\reset-demo-db.ps1 -ConfirmDatabase manhwanet_demo
#>
[CmdletBinding()]
param(
    [string]$ConfirmDatabase
)

. (Join-Path $PSScriptRoot 'demo-common.ps1')

$cfg = Get-DemoDbConfig

if (-not $ConfirmDatabase) {
    Write-Host "[DEMO] 데모 DB '$($cfg.DemoDb)' 의 데이터(계정·작품·별점·리뷰·수집 기록)를 모두 지웁니다. 개발·테스트 DB 는 건드리지 않습니다."
    $ConfirmDatabase = Read-Host "계속하려면 DB 이름 '$($cfg.DemoDb)' 를 입력하세요"
}
if ($ConfirmDatabase -cne $cfg.DemoDb) {
    Write-Host "[DEMO] 입력한 이름이 데모 DB 이름과 달라 취소했습니다. 아무것도 지우지 않았습니다."
    exit 1
}

Invoke-DemoPsql $cfg "DROP DATABASE IF EXISTS `"$($cfg.DemoDb)`" WITH (FORCE)" | Out-Null
Invoke-DemoPsql $cfg "CREATE DATABASE `"$($cfg.DemoDb)`"" | Out-Null
Write-Host "[DEMO] 데모 DB '$($cfg.DemoDb)' 를 비웠습니다. 다음: cd backend; .\gradlew.bat demo -Pstep=all"
