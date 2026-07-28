#requires -Version 7
<#
.SYNOPSIS
    Set the support status of a version in Fill (SUPPORTED / DEPRECATED /
    UNSUPPORTED) via the GraphQL management API, e.g. to deprecate old
    Minecraft versions. Versions are auto-created as SUPPORTED on first publish.

.DESCRIPTION
    Talks to the Fill API over HTTPS using the admin credentials from
    scripts/fill.config.psd1 (copy fill.config.example.psd1 and fill it in).

.EXAMPLE
    ./scripts/fill-support.ps1 -Version 26.2 -Status DEPRECATED
#>
[CmdletBinding()]
param(
    # Version id, e.g. '26.2'.
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][ValidateSet('SUPPORTED', 'DEPRECATED', 'UNSUPPORTED')][string]$Status,
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

# --- Update the version's support status --------------------------------------
$data = Invoke-FillGraphQL 'mutation($input: UpdateVersionInput!) {
    updateVersion(input: $input) { version { key support { status } } }
}' @{
    input = @{
        project = $ProjectKey
        key     = $Version
        support = @{ status = $Status }
    }
}

Info "Version '$($data.updateVersion.version.key)' -> $($data.updateVersion.version.support.status)"
