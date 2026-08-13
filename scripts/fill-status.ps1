#requires -Version 7
<#
.SYNOPSIS
    Show the state of the Scissors Fill instance: families, versions, support
    status, Java metadata, and the latest build per version.

.DESCRIPTION
    Read-only; talks to the public Fill API over HTTPS (no SSH needed).
    Reads ApiUrl/ProjectKey from scripts/fill.config.psd1 if present, otherwise
    uses the defaults for the Scissors instance.

.EXAMPLE
    ./scripts/fill-status.ps1
    ./scripts/fill-status.ps1 -CheckDownload
#>
[CmdletBinding()]
param(
    # Path to the config file. Defaults to fill.config.psd1 next to this script.
    [string]$ConfigPath,
    # Also HEAD-request each latest build's download URL to verify it serves.
    [switch]$CheckDownload
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'fill.config.psd1' }
$cfg = @{}
if (Test-Path -LiteralPath $ConfigPath) { $cfg = Import-PowerShellDataFile -LiteralPath $ConfigPath }

function Cfg([string]$key, $default) {
    if ($cfg.ContainsKey($key) -and $null -ne $cfg[$key] -and "$($cfg[$key])" -ne '') { return $cfg[$key] }
    return $default
}

$ApiUrl  = (Cfg 'ApiUrl' 'https://fill.scissors.gg').ToString().TrimEnd('/')
$Project = Cfg 'ProjectKey' 'scissors'
$NumbersUrl   = (Cfg 'BuildNumbersUrl' 'https://numbers.scissors.gg').ToString().TrimEnd('/')
$NumbersToken = Cfg 'BuildNumbersToken' ''
$TrackPrefix  = Cfg 'TrackPrefix' $Project

# With a token configured, fetch the build-number tracks once so each version's
# counter can be compared against its latest published build.
$tracks = $null
if ($NumbersToken) {
    try {
        $response = Invoke-RestMethod "$NumbersUrl/v1/tracks" -Headers @{ Authorization = "Bearer $NumbersToken" }
        $tracks = @{}
        foreach ($t in @($response.tracks)) { $tracks[$t.track] = [int]$t.number }
    } catch {
        Write-Host "WARNING: cannot reach $NumbersUrl/v1/tracks : $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

try {
    $info = Invoke-RestMethod "$ApiUrl/v3/projects/$Project"
} catch {
    Fail "Cannot reach $ApiUrl/v3/projects/$Project : $($_.Exception.Message)"
}

Write-Host "$($info.project.name) ($($info.project.id)) @ $ApiUrl" -ForegroundColor Cyan

foreach ($familyProp in $info.versions.PSObject.Properties) {
    $family = $familyProp.Name
    Write-Host "`nfamily $family" -ForegroundColor Yellow

    foreach ($versionId in $familyProp.Value) {
        try {
            $ver = Invoke-RestMethod "$ApiUrl/v3/projects/$Project/versions/$versionId"
        } catch {
            Write-Host "  $versionId : failed to fetch ($($_.Exception.Message))" -ForegroundColor Red
            continue
        }

        $flagCount = @($ver.version.java.flags.recommended).Count
        $buildCount = @($ver.builds).Count
        Write-Host ("  {0}  support={1}  java>={2}  flags={3}  builds={4}" -f `
            $versionId, $ver.version.support.status, $ver.version.java.version.minimum, $flagCount, $buildCount)

        if ($buildCount -eq 0) {
            Write-Host "    (no builds published yet)" -ForegroundColor DarkGray
            continue
        }

        try {
            $latest = Invoke-RestMethod "$ApiUrl/v3/projects/$Project/versions/$versionId/builds/latest"
        } catch {
            Write-Host "    latest build: failed to fetch ($($_.Exception.Message))" -ForegroundColor Red
            continue
        }

        $download = $latest.downloads.'server:default'
        $sizeMb = [math]::Round($download.size / 1MB, 1)
        Write-Host ("    latest: #{0}  {1}  {2}  {3} ({4} MB)" -f `
            $latest.id, $latest.channel, $latest.time, $download.name, $sizeMb)
        Write-Host "    $($download.url)" -ForegroundColor DarkGray

        if ($null -ne $tracks) {
            $trackName = "$TrackPrefix-$versionId"
            if (-not $tracks.ContainsKey($trackName)) {
                Write-Host "    track ${trackName}: MISSING - next CI publish would allocate 1 and collide; seed it with build-numbers.ps1" -ForegroundColor Red
            } elseif ($tracks[$trackName] -lt [int]$latest.id) {
                Write-Host "    track ${trackName}: $($tracks[$trackName]) is BEHIND latest build $($latest.id); next allocation would collide" -ForegroundColor Red
            } else {
                Write-Host "    track ${trackName}: $($tracks[$trackName]) (next: $($tracks[$trackName] + 1))" -ForegroundColor Green
            }
        }

        if ($CheckDownload) {
            try {
                $head = Invoke-WebRequest -Method Head -Uri $download.url
                $ok = [long]$head.Headers['Content-Length'][0] -eq [long]$download.size
                if ($ok) {
                    Write-Host "    download check: OK ($($download.size) bytes)" -ForegroundColor Green
                } else {
                    Write-Host "    download check: size mismatch (API says $($download.size), server says $($head.Headers['Content-Length'][0]))" -ForegroundColor Red
                }
            } catch {
                Write-Host "    download check: FAILED ($($_.Exception.Message))" -ForegroundColor Red
            }
        }
    }
}
