[CmdletBinding()]
param(
    [ValidateSet("frontend", "backend", "all")]
    [string]$Target = "frontend",
    [ValidatePattern("^[A-Za-z0-9_.@:-]+$")]
    [string]$RemoteHost = "megumin",
    [ValidatePattern("^/[A-Za-z0-9._/-]+$")]
    [string]$RemoteRepo = "/root/projects/agent-drive",
    [switch]$SkipTests,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$FrontendRoot = Join-Path $RepoRoot "frontend"
$BackendRoot = Join-Path $RepoRoot "backend"
$DeployId = [Guid]::NewGuid().ToString("N")
$RemoteArchive = "/tmp/agent-drive-out-$DeployId.tar"
$RemoteJar = "/tmp/agent-drive-backend-$DeployId.jar"
$RemoteApiUnit = "/tmp/agent-drive-java-$DeployId.service"
$RemoteWorkerUnit = "/tmp/agent-drive-java-worker-$DeployId.service"
$RemoteBackupScript = "/tmp/agent-drive-java-backup-$DeployId.sh"
$RemoteBackupUnit = "/tmp/agent-drive-java-backup-$DeployId.service"
$RemoteBackupTimer = "/tmp/agent-drive-java-backup-$DeployId.timer"

function Require-Command {
    param([Parameter(Mandatory)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    Write-Host (">> " + $Command + " " + ($Arguments -join " ")) -ForegroundColor DarkGray
    & $Command @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $Command"
    }
}

function Invoke-RemoteBash {
    param([Parameter(Mandatory)][string]$Script)

    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Script))
    Invoke-Checked "ssh" @($RemoteHost, "echo $encoded | base64 -d | bash")
}

function Update-FrontendCacheVersion {
    $serviceWorker = Join-Path $FrontendRoot "public/sw.js"
    $content = [IO.File]::ReadAllText($serviceWorker)
    $pattern = 'const CACHE = "agent-drive-v(\d+)";'
    $match = [regex]::Match($content, $pattern)
    if (-not $match.Success) {
        throw "Service Worker cache version not found: $serviceWorker"
    }

    $next = [int]$match.Groups[1].Value + 1
    $replacement = 'const CACHE = "agent-drive-v' + $next + '";'
    $updated = $content.Substring(0, $match.Index) + $replacement +
        $content.Substring($match.Index + $match.Length)
    [IO.File]::WriteAllText($serviceWorker, $updated, [Text.UTF8Encoding]::new($false))
    Write-Host "Service Worker cache: v$next" -ForegroundColor DarkGray
}

function Invoke-FrontendBuild {
    $serviceWorker = Join-Path $FrontendRoot "public/sw.js"
    $originalCache = $null
    $cacheUpdated = $false
    Push-Location $FrontendRoot
    try {
        if (-not $SkipBuild) {
            if (-not $SkipTests) {
                Invoke-Checked "npm" @("run", "lint")
                Invoke-Checked "npm" @("test")
            }
            $originalCache = [IO.File]::ReadAllText($serviceWorker)
            Update-FrontendCacheVersion
            $cacheUpdated = $true
            Invoke-Checked "npm" @("run", "build")
        }
    }
    catch {
        if ($cacheUpdated) {
            [IO.File]::WriteAllText($serviceWorker, $originalCache, [Text.UTF8Encoding]::new($false))
            Write-Warning "Frontend build failed; restored the previous Service Worker cache version."
        }
        throw
    }
    finally {
        Pop-Location
    }

    $out = Join-Path $FrontendRoot "out"
    if (-not (Test-Path (Join-Path $out "index.html"))) {
        throw "Frontend build output is missing: $out/index.html"
    }
    if (-not (Test-Path (Join-Path $out ".well-known/assetlinks.json"))) {
        throw "Frontend build output is missing .well-known/assetlinks.json"
    }
    return $out
}

function New-FrontendArchive {
    param([Parameter(Mandatory)][string]$OutDirectory)

    $archive = Join-Path ([IO.Path]::GetTempPath()) "agent-drive-out-$DeployId.tar"
    Invoke-Checked "tar" @("-cf", $archive, "-C", $OutDirectory, ".")
    return $archive
}

function Deploy-Frontend {
    param([Parameter(Mandatory)][string]$OutDirectory)

    $archive = New-FrontendArchive $OutDirectory
    try {
        Invoke-Checked "scp" @($archive, "${RemoteHost}:$RemoteArchive")

        $remoteScript = @'
set -euo pipefail
repo='__REMOTE_REPO__'
archive='__REMOTE_ARCHIVE__'
out="$repo/frontend/out"
stage="$repo/frontend/.out.new.$$"
backup="$repo/frontend/.out-backup.$(date +%Y%m%d%H%M%S).$$"

test -f "$archive"
test -d "$out"
mkdir "$stage"
trap 'rm -rf "$stage"' EXIT
tar --warning=no-timestamp -xf "$archive" -C "$stage"
test -f "$stage/index.html"
test -f "$stage/.well-known/assetlinks.json"

# next build does not produce the release APK; retain the existing download artifact.
if [ -f "$out/app/agent-drive.apk" ] && [ ! -f "$stage/app/agent-drive.apk" ]; then
  mkdir -p "$stage/app"
  cp -a "$out/app/agent-drive.apk" "$stage/app/agent-drive.apk"
fi
chmod -R a+rX "$stage"
mv "$out" "$backup"
mv "$stage" "$out"
rm -f "$archive"
trap - EXIT
printf 'frontend deployed: %s\nbackup kept: %s\n' "$out" "$backup"
'@
        $remoteScript = $remoteScript.Replace("__REMOTE_REPO__", $RemoteRepo).Replace("__REMOTE_ARCHIVE__", $RemoteArchive)
        Invoke-RemoteBash $remoteScript

        $healthScript = @'
set -euo pipefail
curl --fail --silent --show-error http://127.0.0.1:8000/api/v1/health >/dev/null
grep -R --binary-files=without-match -q 'ID:' '__REMOTE_REPO__/frontend/out/_next/static'
grep -q 'agent-drive-v' '__REMOTE_REPO__/frontend/out/sw.js'
printf 'frontend health and session ID marker: OK\n'
'@
        $healthScript = $healthScript.Replace("__REMOTE_REPO__", $RemoteRepo)
        Invoke-RemoteBash $healthScript
    }
    finally {
        if (Test-Path $archive) {
            Remove-Item -LiteralPath $archive -Force
        }
    }
}

function Invoke-BackendBuild {
    Push-Location $BackendRoot
    try {
        if (-not $SkipBuild) {
            if (-not $SkipTests) {
                Invoke-Checked "mvn" @("-q", "test")
            }
            Invoke-Checked "mvn" @("-q", "-DskipTests", "package")
        }
    }
    finally {
        Pop-Location
    }

    $artifact = Get-ChildItem -LiteralPath (Join-Path $BackendRoot "target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*-plain.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $artifact) {
        throw "Java artifact not found under $BackendRoot/target"
    }
    return $artifact.FullName
}

function Deploy-Backend {
    param([Parameter(Mandatory)][string]$Artifact)

    $apiUnit = Join-Path $RepoRoot "deploy/agent-drive-java.service"
    $workerUnit = Join-Path $RepoRoot "deploy/agent-drive-java-worker.service"
    $backupScript = Join-Path $RepoRoot "scripts/backup-java.sh"
    $backupUnit = Join-Path $RepoRoot "deploy/agent-drive-java-backup.service"
    $backupTimer = Join-Path $RepoRoot "deploy/agent-drive-java-backup.timer"
    if (-not (Test-Path $apiUnit) -or -not (Test-Path $workerUnit) -or
        -not (Test-Path $backupScript) -or -not (Test-Path $backupUnit) -or -not (Test-Path $backupTimer)) {
        throw "Java systemd unit files are missing under $RepoRoot/deploy"
    }

    Invoke-Checked "scp" @($Artifact, "${RemoteHost}:$RemoteJar")
    Invoke-Checked "scp" @($apiUnit, "${RemoteHost}:$RemoteApiUnit")
    Invoke-Checked "scp" @($workerUnit, "${RemoteHost}:$RemoteWorkerUnit")
    Invoke-Checked "scp" @($backupScript, "${RemoteHost}:$RemoteBackupScript")
    Invoke-Checked "scp" @($backupUnit, "${RemoteHost}:$RemoteBackupUnit")
    Invoke-Checked "scp" @($backupTimer, "${RemoteHost}:$RemoteBackupTimer")

    $remoteScript = @'
set -euo pipefail
artifact='__REMOTE_JAR__'
api_unit='__REMOTE_API_UNIT__'
worker_unit='__REMOTE_WORKER_UNIT__'
backup_script='__REMOTE_BACKUP_SCRIPT__'
backup_unit='__REMOTE_BACKUP_UNIT__'
backup_timer='__REMOTE_BACKUP_TIMER__'

test -f "$artifact"
test -f "$api_unit"
test -f "$worker_unit"
test -f "$backup_script"
test -f "$backup_unit"
test -f "$backup_timer"
install -m 0644 "$api_unit" /etc/systemd/system/agent-drive-java.service
install -m 0644 "$worker_unit" /etc/systemd/system/agent-drive-java-worker.service
install -m 0750 "$backup_script" '__REMOTE_REPO__/scripts/backup-java.sh'
install -m 0644 "$backup_unit" /etc/systemd/system/agent-drive-java-backup.service
install -m 0644 "$backup_timer" /etc/systemd/system/agent-drive-java-backup.timer
install -m 0644 "$artifact" /opt/agent-drive-java/agent-drive-backend.jar
systemd-analyze verify /etc/systemd/system/agent-drive-java.service /etc/systemd/system/agent-drive-java-worker.service /etc/systemd/system/agent-drive-java-backup.service /etc/systemd/system/agent-drive-java-backup.timer
systemctl daemon-reload
systemctl disable --now agent-drive-backup.timer 2>/dev/null || true
rm -f /etc/systemd/system/agent-drive-backup.service /etc/systemd/system/agent-drive-backup.timer
systemctl daemon-reload
systemctl enable agent-drive-java.service agent-drive-java-worker.service agent-drive-java-backup.timer
systemctl start agent-drive-java-backup.timer
systemctl restart agent-drive-java.service

healthy=0
for _ in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:8000/api/v1/health >/dev/null; then
    healthy=1
    break
  fi
  sleep 1
done
if [ "$healthy" -ne 1 ]; then
  journalctl -u agent-drive-java.service -n 80 --no-pager
  exit 1
fi

systemctl restart agent-drive-java-worker.service
systemctl is-active --quiet agent-drive-java.service
systemctl is-active --quiet agent-drive-java-worker.service
rm -f "$artifact" "$api_unit" "$worker_unit" "$backup_script" "$backup_unit" "$backup_timer"
printf 'Java API/Worker services and backup timer: OK\n'
'@
    $remoteScript = $remoteScript.Replace("__REMOTE_JAR__", $RemoteJar)
    $remoteScript = $remoteScript.Replace("__REMOTE_API_UNIT__", $RemoteApiUnit)
    $remoteScript = $remoteScript.Replace("__REMOTE_WORKER_UNIT__", $RemoteWorkerUnit)
    $remoteScript = $remoteScript.Replace("__REMOTE_BACKUP_SCRIPT__", $RemoteBackupScript)
    $remoteScript = $remoteScript.Replace("__REMOTE_BACKUP_UNIT__", $RemoteBackupUnit)
    $remoteScript = $remoteScript.Replace("__REMOTE_BACKUP_TIMER__", $RemoteBackupTimer)
    $remoteScript = $remoteScript.Replace("__REMOTE_REPO__", $RemoteRepo)
    try {
        Invoke-RemoteBash $remoteScript
    }
    finally {
        # The remote script removes these files after a successful install. Clean failed uploads too.
        $cleanup = "rm -f '$RemoteJar' '$RemoteApiUnit' '$RemoteWorkerUnit' '$RemoteBackupScript' '$RemoteBackupUnit' '$RemoteBackupTimer'"
        try { Invoke-RemoteBash $cleanup } catch { Write-Warning $_.Exception.Message }
    }
}

Require-Command "ssh"
Require-Command "scp"

$frontendOut = $null
$backendArtifact = $null
if ($Target -in @("frontend", "all")) {
    Require-Command "npm"
    Require-Command "tar"
    $frontendOut = Invoke-FrontendBuild
}
if ($Target -in @("backend", "all")) {
    Require-Command "mvn"
    $backendArtifact = Invoke-BackendBuild
}

if ($Target -in @("frontend", "all")) {
    Deploy-Frontend $frontendOut
}
if ($Target -in @("backend", "all")) {
    Deploy-Backend $backendArtifact
}

Write-Host "Deployment complete: $Target" -ForegroundColor Green
