<#
.SYNOPSIS
  데모 전용 DB(DEMO_DB_NAME, 기본 manhwanet_demo)가 없으면 만듭니다 (#67).

.DESCRIPTION
  이미 있으면 아무것도 바꾸지 않습니다. 데이터를 지우지 않습니다.
  docker compose 로 띄운 postgres 컨테이너를 사용합니다(저장소 루트 .env 기준).

.EXAMPLE
  .\scripts\demo\create-demo-db.ps1
#>
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'demo-common.ps1')

$cfg = Get-DemoDbConfig
if (Test-DemoDbExists $cfg) {
    Write-Host "[DEMO] 데모 DB '$($cfg.DemoDb)' 가 이미 있습니다. 변경하지 않았습니다."
    exit 0
}
Invoke-DemoPsql $cfg "CREATE DATABASE `"$($cfg.DemoDb)`"" | Out-Null
Write-Host "[DEMO] 데모 DB '$($cfg.DemoDb)' 를 만들었습니다. 다음: cd backend; .\gradlew.bat demo -Pstep=all"
