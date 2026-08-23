[CmdletBinding()]
param(
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BackendRoot = Join-Path $RepoRoot "backend"
$FrontendRoot = Join-Path $RepoRoot "frontend"
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $BackendRoot "target/agent-eval-dashboard.md"
}

function Invoke-TestGroup {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )
    Write-Host (">> " + $Name) -ForegroundColor DarkGray
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments | Out-Host
        return $LASTEXITCODE -eq 0
    }
    finally {
        Pop-Location
    }
}

$results = [ordered]@{}
$results["backend-agent"] = Invoke-TestGroup "backend agent behavior" "mvn" @(
    "-q", "-Dtest=LangChainAgentRuntimeTest,ChatBackendApiOperationsTest,DefaultChatContextProviderTest,ChatRunRegistryTest,BackendApiToolTest,PlanToolTest" , "test"
) $BackendRoot
$results["backend-files"] = Invoke-TestGroup "backend file workflow" "mvn" @(
    "-q", "-Dtest=MybatisFileStorageServiceListTest,FileControllerContractTest,ChatBackendApiFileHandlerTest" , "test"
) $BackendRoot
$results["frontend-activity"] = Invoke-TestGroup "frontend activity behavior" "npm" @(
    "test", "--", "--run", "src/components/chat/components.test.tsx", "src/components/chat/ChatPanel.test.tsx", "src/components/chat/chat-stream-state.test.ts"
) $FrontendRoot
$results["frontend-lint"] = Invoke-TestGroup "frontend lint" "npm" @("run", "lint") $FrontendRoot

$failed = @($results.Values | Where-Object { -not $_ })
$overall = $failed.Count -eq 0
$timestamp = (Get-Date).ToUniversalTime().ToString("o")
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# Agent Eval Dashboard")
$lines.Add("")
$lines.Add("Generated: $timestamp")
$lines.Add("")
$lines.Add("| Suite | Status |")
$lines.Add("|-------|--------|")
foreach ($entry in $results.GetEnumerator()) {
    $status = if ($entry.Value) { "PASS" } else { "FAIL" }
    $lines.Add("| ``$($entry.Key)`` | $status |")
}
$lines.Add("")
$lines.Add("Overall: **$(if ($overall) { 'PASS' } else { 'FAIL' })**")
$lines.Add("")
$lines.Add("The detailed behavior matrix is maintained in ``docs/agent-evals.md``.")

$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
[IO.File]::WriteAllLines($ReportPath, $lines, [Text.UTF8Encoding]::new($false))
$lines | Out-Host
if (-not $overall) { exit 1 }
