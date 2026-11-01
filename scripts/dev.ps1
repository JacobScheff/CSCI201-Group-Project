param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$rallyRoot = Split-Path -Parent $PSScriptRoot
$rallyMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($rallyMavenCommand) {
    $rallyMaven = $rallyMavenCommand.Source
} else {
    $rallyTools = Join-Path $rallyRoot 'tmp/tools'
    $rallyMaven = Join-Path $rallyTools 'apache-maven-3.9.11/bin/mvn.cmd'
    if (-not (Test-Path -LiteralPath $rallyMaven)) {
        New-Item -ItemType Directory -Force -Path $rallyTools | Out-Null
        $rallyArchive = Join-Path $rallyTools 'maven.zip'
        Write-Host 'Downloading Maven to the project-local tools folder...'
        Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile $rallyArchive
        Expand-Archive -LiteralPath $rallyArchive -DestinationPath $rallyTools -Force
    }
}
$rallyJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
Push-Location (Join-Path $rallyRoot 'backend')
try {
    & $rallyMaven -B -ntp '-Dmaven.repo.local=../tmp/m2' package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    $rallyBackend = Start-Process -FilePath $rallyJava -ArgumentList @('-jar', 'target/rally-1.0.0.jar', '--spring.profiles.active=local') -WorkingDirectory (Join-Path $rallyRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $rallyRoot 'tmp/backend.log') -RedirectStandardError (Join-Path $rallyRoot 'tmp/backend-error.log')
} finally { Pop-Location }
try {
    $rallyReady = $false
    for ($rallyAttempt = 0; $rallyAttempt -lt 30; $rallyAttempt++) {
        if ($rallyBackend.HasExited) { throw 'Backend exited. See tmp/backend.log and tmp/backend-error.log.' }
        try { $rallyHealth = Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 2; if ($rallyHealth.status -eq 'ok') { $rallyReady = $true; break } } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $rallyReady) { throw 'Backend did not become ready. See tmp/backend.log.' }
    Push-Location (Join-Path $rallyRoot 'frontend')
    try {
        if (-not $SkipInstall) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
        Write-Host 'Open http://127.0.0.1:5173. Press Ctrl+C to stop both services.'
        & npm.cmd run dev -- --port 5173 --strictPort
    } finally { Pop-Location }
} finally {
    if ($rallyBackend -and -not $rallyBackend.HasExited) { Stop-Process -Id $rallyBackend.Id }
}
