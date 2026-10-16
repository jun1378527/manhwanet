# 데모 DB 스크립트 공통 함수 (#67). 직접 실행하지 않고 다른 스크립트가 불러 씁니다.
# Windows PowerShell 5.1 과 PowerShell 7 에서 모두 동작하도록 작성했습니다.

$ErrorActionPreference = 'Stop'

$script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Read-DotEnv {
    # 루트 .env 를 읽습니다. 같은 이름의 환경변수가 있으면 환경변수가 우선합니다(Docker Compose·Spring 과 같은 규칙).
    $values = @{}
    $path = Join-Path $script:RepoRoot '.env'
    if (Test-Path -LiteralPath $path) {
        foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#')) { continue }
            $i = $t.IndexOf('=')
            if ($i -lt 1) { continue }
            $values[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
        }
    }
    return $values
}

function Get-DemoSetting([hashtable]$DotEnv, [string]$Key, [string]$Default) {
    $fromEnv = [Environment]::GetEnvironmentVariable($Key)
    if ($null -ne $fromEnv -and $fromEnv -ne '') { return $fromEnv.Trim() }
    if ($DotEnv.ContainsKey($Key) -and $DotEnv[$Key] -ne '') { return $DotEnv[$Key] }
    return $Default
}

function Get-DemoDbConfig {
    $dotEnv = Read-DotEnv
    $cfg = @{
        DemoDb = Get-DemoSetting $dotEnv 'DEMO_DB_NAME' 'manhwanet_demo'
        DevDb  = Get-DemoSetting $dotEnv 'DB_NAME' 'manhwanet'
        TestDb = Get-DemoSetting $dotEnv 'TEST_DB_NAME' 'manhwanet_test'
        User   = Get-DemoSetting $dotEnv 'DB_USERNAME' ''
    }
    if ($cfg.User -eq '') { throw 'DB_USERNAME 이 없습니다. .env.example 을 복사해 .env 를 만드세요.' }
    Assert-DemoDbName $cfg.DemoDb $cfg.DevDb $cfg.TestDb
    return $cfg
}

function Assert-DemoDbName([string]$DemoDb, [string]$DevDb, [string]$TestDb) {
    # Backend DemoDatabaseNames 와 같은 규칙: 소문자·숫자·'_' 1~63자, '_demo' 로 끝남, 개발·테스트 DB 와 다름
    if ($DemoDb -cnotmatch '^[a-z0-9_]{1,63}$' -or -not $DemoDb.EndsWith('_demo')) {
        throw "DEMO_DB_NAME '$DemoDb' 은 소문자·숫자·'_' 로 이루어지고 '_demo' 로 끝나야 합니다."
    }
    if ($DemoDb -eq $DevDb.Trim() -or $DemoDb -eq $TestDb.Trim()) {
        throw "DEMO_DB_NAME '$DemoDb' 은 DB_NAME·TEST_DB_NAME 과 달라야 합니다."
    }
}

function Invoke-DemoPsql([hashtable]$Cfg, [string]$Sql) {
    # docker compose 의 postgres 컨테이너 안에서 관리용 DB(postgres)에 접속해 SQL 한 문장을 실행합니다.
    # 값은 Assert-DemoDbName 으로 검사한 DB 이름만 SQL 에 들어갑니다.
    Push-Location $script:RepoRoot
    try {
        $out = & docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -X -q -t -A -U $Cfg.User -d postgres -c $Sql
        if ($LASTEXITCODE -ne 0) { throw "psql 실행 실패(종료 코드 $LASTEXITCODE). 'docker compose ps' 로 postgres 가 healthy 인지 확인하세요." }
        return $out
    } finally {
        Pop-Location
    }
}

function Test-DemoDbExists([hashtable]$Cfg) {
    $r = Invoke-DemoPsql $Cfg "SELECT 1 FROM pg_database WHERE datname = '$($Cfg.DemoDb)'"
    return ("$r".Trim() -eq '1')
}
