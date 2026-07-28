#requires -Version 7
<#
.SYNOPSIS
    Create or update a Fill version family via the GraphQL management API. Run
    this BEFORE the first CI publish of a new Minecraft version, or the publish
    fails with a 404 (publishing auto-creates versions, but not families).

.DESCRIPTION
    Talks to the Fill API over HTTPS using the admin credentials from
    scripts/fill.config.psd1 (copy fill.config.example.psd1 and fill it in).

    Idempotent: creates the family if missing, otherwise updates it, with the
    given minimum Java version and recommended JVM flags (defaults to Aikar's
    flags, same as Paper serves).

.EXAMPLE
    ./scripts/fill-family.ps1 -Family 26.3
    ./scripts/fill-family.ps1 -Family 26.3 -JavaMinimum 25
#>
[CmdletBinding()]
param(
    # Version family key, e.g. '26.3'. Must match what fill-gradle derives from
    # mcVersion (the first two numeric components).
    [Parameter(Mandatory)][string]$Family,
    # Minimum Java version served as metadata for this family.
    [int]$JavaMinimum = 25,
    # Recommended JVM flags. Defaults to Aikar's flags.
    [string[]]$Flags = @(
        '-XX:+AlwaysPreTouch',
        '-XX:+DisableExplicitGC',
        '-XX:+ParallelRefProcEnabled',
        '-XX:+PerfDisableSharedMem',
        '-XX:+UnlockExperimentalVMOptions',
        '-XX:+UseG1GC',
        '-XX:G1HeapRegionSize=8M',
        '-XX:G1HeapWastePercent=5',
        '-XX:G1MaxNewSizePercent=40',
        '-XX:G1MixedGCCountTarget=4',
        '-XX:G1MixedGCLiveThresholdPercent=90',
        '-XX:G1NewSizePercent=30',
        '-XX:G1RSetUpdatingPauseTimePercent=5',
        '-XX:G1ReservePercent=20',
        '-XX:InitiatingHeapOccupancyPercent=15',
        '-XX:MaxGCPauseMillis=200',
        '-XX:MaxTenuringThreshold=1',
        '-XX:SurvivorRatio=32'
    ),
    # Path to the config file. Defaults to fill.config.psd1 next to this script.
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

function Info([string]$message) {
    Write-Host $message -ForegroundColor Cyan
}

# --- Load config ------------------------------------------------------------
if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'fill.config.psd1' }
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Fail "Config not found: $ConfigPath`n       Copy scripts/fill.config.example.psd1 to scripts/fill.config.psd1 and fill it in."
}
$cfg = Import-PowerShellDataFile -LiteralPath $ConfigPath

function Cfg([string]$key, $default) {
    if ($cfg.ContainsKey($key) -and $null -ne $cfg[$key] -and "$($cfg[$key])" -ne '') { return $cfg[$key] }
    return $default
}

$ApiUrl        = (Cfg 'ApiUrl' 'https://fill.scissors.gg').ToString().TrimEnd('/')
$ProjectKey    = Cfg 'ProjectKey' 'scissors'
$AdminUser     = Cfg 'AdminUser' 'admin'
$AdminPassword = Cfg 'AdminPassword' ''
if (-not $AdminPassword) { Fail "AdminPassword is not set in $ConfigPath." }

# --- GraphQL helper ----------------------------------------------------------
$basic = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("${AdminUser}:${AdminPassword}"))

function Invoke-FillGraphQL([string]$query, [hashtable]$variables) {
    $body = @{ query = $query; variables = $variables } | ConvertTo-Json -Depth 10 -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$ApiUrl/graphql" `
        -Headers @{ Authorization = "Basic $basic" } `
        -ContentType 'application/json' -Body $body
    if ($response.PSObject.Properties['errors'] -and $response.errors) {
        Fail ("GraphQL error: " + (($response.errors | ForEach-Object { $_.message }) -join '; '))
    }
    return $response.data
}

# --- Create or update the family ---------------------------------------------
$exists = Invoke-FillGraphQL 'query($project: String!, $family: String!) {
    project(key: $project) { family(key: $family) { key } }
}' @{ project = $ProjectKey; family = $Family }

if ($null -eq $exists.project) { Fail "Project '$ProjectKey' does not exist on $ApiUrl." }

$input = @{
    project = $ProjectKey
    key     = $Family
    java    = @{
        version = @{ minimum = $JavaMinimum }
        flags   = @{ recommended = @($Flags) }
    }
}

if ($null -eq $exists.project.family) {
    $data = Invoke-FillGraphQL 'mutation($input: CreateFamilyInput!) {
        createFamily(input: $input) { family { key } }
    }' @{ input = $input }
    Info "Created family '$($data.createFamily.family.key)' (java >= $JavaMinimum, $(@($Flags).Count) flags)."
} else {
    $data = Invoke-FillGraphQL 'mutation($input: UpdateFamilyInput!) {
        updateFamily(input: $input) { family { key } }
    }' @{ input = $input }
    Info "Updated family '$($data.updateFamily.family.key)' (java >= $JavaMinimum, $(@($Flags).Count) flags)."
}
