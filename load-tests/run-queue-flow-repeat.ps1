# queue-flow.js 동일 조건으로 N회 연속 실행 → k6 summary JSON만 저장 (로그 비교용)
# 전제: 완전 콜드(매 런마다 Redis/캐시/큐 초기화)가 아니라, 웜 상태에서 분산만 보는 용도.
# 사용: 앱에서 Hikari pool 등 설정 반영 후 재시작 → 이 스크립트만 실행.
#
# 예:
#   .\run-queue-flow-repeat.ps1 -BaseUrl "http://172.31.x.x:8080" -ConcertId 43 -Repeat 3 `
#     -PeakVu 800 -PollSleepSec 0.005 -Tag "pool10-800vu"
#
# stress+ (문서 §4.1)와 맞추려면 -PeakVu 1500 -PollSleepSec 0.002 등으로 바꾼다.

param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$ConcertId,
    [string]$Tag = "run",
    [int]$Repeat = 3,
    [int]$PeakVu = 800,
    [double]$PollSleepSec = 0.005,
    [string]$Profile = "stress",
    [string]$OutDir = "k6-repeat-out"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

for ($i = 1; $i -le $Repeat; $i++) {
    $json = Join-Path $OutDir ("summary-{0}-run{1:D2}-{2}.json" -f $Tag, $i, $stamp)
    Write-Host "=== $Tag run $i / $Repeat -> $json ===" -ForegroundColor Cyan
    k6 run `
        -e "BASE_URL=$BaseUrl" `
        -e "CONCERT_ID=$ConcertId" `
        -e "K6_PEAK_VU=$PeakVu" `
        -e "K6_QUEUE_POLL_SLEEP_SEC=$PollSleepSec" `
        -e "K6_PROFILE=$Profile" `
        -e "K6_WARM_DURATION=5s" -e "K6_MID_DURATION=5s" -e "K6_CLIMB_DURATION=10s" `
        -e "K6_PEAK_HOLD=35s" -e "K6_RAMP_DOWN=5s" `
        --summary-export $json `
        (Join-Path $PSScriptRoot "queue-flow.js")
    if ($i -lt $Repeat) {
        Start-Sleep -Seconds 20
    }
}

Write-Host "Done. Compare: k6-repeat-out/summary-*-$stamp.json" -ForegroundColor Green
