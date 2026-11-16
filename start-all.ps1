[CmdletBinding()]
param(
    [switch]$SkipInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeDirectory = Join-Path $ProjectRoot '.runtime'
$BackendDirectory = Join-Path $ProjectRoot 'backend'
$FrontendDirectory = Join-Path $ProjectRoot 'frontend'

New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null

function Assert-Command {
    param([Parameter(Mandatory)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory)][int]$Port,
        [string]$Address = '127.0.0.1'
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync($Address, $Port)
        return $connection.Wait(500) -and $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Test-HttpContent {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][AllowEmptyString()][string]$ExpectedText
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
            return $false
        }
        if ([string]::IsNullOrEmpty($ExpectedText)) {
            return $true
        }
        $content = if ($response.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($response.Content)
        }
        else {
            [string]$response.Content
        }
        return $content.Contains($ExpectedText)
    }
    catch {
        return $false
    }
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Condition,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            Write-Host "[ready] $Name"
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Name after $TimeoutSeconds seconds."
}

function Start-AppProcess {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    $stdout = Join-Path $RuntimeDirectory "$Name.log"
    $stderr = Join-Path $RuntimeDirectory "$Name.err.log"
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "[start] $Name (PID $($process.Id))"
    return $process
}

Assert-Command 'docker'
Assert-Command 'npm.cmd'

Write-Host '[start] PostgreSQL, MinIO, Keycloak, Orthanc, OHIF and imaging AI worker'
& docker compose --project-directory $ProjectRoot -f (Join-Path $ProjectRoot 'docker-compose.yml') up -d --build
if ($LASTEXITCODE -ne 0) {
    throw 'docker compose up failed.'
}

Wait-Until -Name 'PostgreSQL' -Condition { Test-TcpPort -Port 5433 }
Wait-Until -Name 'MinIO' -Condition {
    Test-HttpContent -Uri 'http://127.0.0.1:9100/minio/health/live' -ExpectedText ''
}
Wait-Until -Name 'Keycloak realm' -Condition {
    Test-HttpContent -Uri 'http://127.0.0.1:8081/realms/med-node' -ExpectedText 'med-node'
}
Wait-Until -Name 'Orthanc DICOMweb' -Condition {
    try {
        $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('mednode:mednode-orthanc-dev'))
        $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8042/dicom-web/studies' `
            -Headers @{ Authorization = "Basic $pair" } -TimeoutSec 3
        return $response.StatusCode -eq 200
    }
    catch { return $false }
}
Wait-Until -Name 'OHIF viewer' -Condition {
    Test-HttpContent -Uri 'http://127.0.0.1:3000' -ExpectedText 'OHIF'
}
Wait-Until -Name 'Imaging AI worker' -TimeoutSeconds 300 -Condition {
    Test-HttpContent -Uri 'http://127.0.0.1:8090/health' -ExpectedText '"status":"ok"'
}

$backendProcess = $null
if (Test-HttpContent -Uri 'http://127.0.0.1:8080/api/health' -ExpectedText 'OK') {
    Write-Host '[ready] Backend was already running'
}
else {
    if (Test-TcpPort -Port 8080) {
        throw 'Port 8080 is occupied by a different service.'
    }
    $backendProcess = Start-AppProcess `
        -Name 'backend' `
        -FilePath (Join-Path $BackendDirectory 'mvnw.cmd') `
        -Arguments @('spring-boot:run') `
        -WorkingDirectory $BackendDirectory
    Wait-Until -Name 'Java backend' -TimeoutSeconds 180 -Condition {
        Test-HttpContent -Uri 'http://127.0.0.1:8080/api/health' -ExpectedText 'OK'
    }
}

if (-not $SkipInstall -and -not (Test-Path (Join-Path $FrontendDirectory 'node_modules'))) {
    Write-Host '[install] Frontend dependencies'
    & npm.cmd ci --prefix $FrontendDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'npm ci failed.'
    }
}

$frontendProcess = $null
if (Test-HttpContent -Uri 'http://127.0.0.1:4202/login' -ExpectedText 'Curavence') {
    Write-Host '[ready] Frontend was already running'
}
else {
    if (Test-TcpPort -Port 4202) {
        throw 'Port 4202 is occupied by a different service.'
    }
    $frontendProcess = Start-AppProcess `
        -Name 'frontend' `
        -FilePath (Get-Command 'npm.cmd').Source `
        -Arguments @('start') `
        -WorkingDirectory $FrontendDirectory
    Wait-Until -Name 'Angular frontend' -TimeoutSeconds 180 -Condition {
        Test-HttpContent -Uri 'http://127.0.0.1:4202/login' -ExpectedText 'Curavence'
    }
}

$processState = [ordered]@{
    startedAt = (Get-Date).ToString('o')
    backend = if ($backendProcess) { $backendProcess.Id } else { $null }
    frontend = if ($frontendProcess) { $frontendProcess.Id } else { $null }
}
$processState | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RuntimeDirectory 'processes.json') -Encoding UTF8

Write-Host ''
Write-Host 'Curavence is ready:'
Write-Host '  Application: http://127.0.0.1:4202'
Write-Host '  Backend:     http://127.0.0.1:8080'
Write-Host '  Keycloak:    http://127.0.0.1:8081'
Write-Host '  MinIO:       http://127.0.0.1:9101'
Write-Host '  Orthanc:     http://127.0.0.1:8042'
Write-Host '  OHIF:        http://127.0.0.1:3000'
Write-Host '  Imaging AI:  http://127.0.0.1:8090'
Write-Host "  Test users:  $ProjectRoot\DANE_LOGOWANIA_TESTOWE.txt"
Write-Host "  Logs:        $RuntimeDirectory"
