#requires -Version 7
<#
.SYNOPSIS
    Manage build number tracks on the build-numbers worker: list all tracks,
    read one, seed a new one, or delete one.

.DESCRIPTION
    Talks to the authenticated build-numbers Cloudflare Worker
    (https://github.com/ScissorsMC/build-numbers) using BuildNumbersUrl and
    BuildNumbersToken from scripts/fill.config.psd1.

    CI allocates numbers itself (POST /next) during publish; this script is for
    inspection and for seeding. Seed a track BEFORE its first CI publish when
    builds already exist in Fill, so numbering continues instead of restarting
    at 1. An unknown track is auto-created at 1 on first allocation, so a brand
    new version needs no seeding.

.EXAMPLE
    ./scripts/build-numbers.ps1
    ./scripts/build-numbers.ps1 scissors-26.2
    ./scripts/build-numbers.ps1 scissors-26.2 -Seed 19
    ./scripts/build-numbers.ps1 scissors-26.2 -Delete
#>
[CmdletBinding(DefaultParameterSetName = 'List')]
param(
    # Track name, e.g. 'scissors-26.2'.
    [Parameter(Mandatory, ParameterSetName = 'Get', Position = 0)]
    [Parameter(Mandatory, ParameterSetName = 'Seed', Position = 0)]
    [Parameter(Mandatory, ParameterSetName = 'Delete', Position = 0)]
    [string]$Track,
    # Create the track with this as its current (last used) number; the next
    # allocation returns Seed + 1. Fails if the track already exists.
    [Parameter(Mandatory, ParameterSetName = 'Seed')][ValidateRange(0, [int]::MaxValue)][int]$Seed,
    # Delete the track. Permanent; the next allocation would recreate it at 1.
    [Parameter(Mandatory, ParameterSetName = 'Delete')][switch]$Delete,
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

$BaseUrl = (Cfg 'BuildNumbersUrl' 'https://numbers.scissors.gg').ToString().TrimEnd('/')
$Token   = Cfg 'BuildNumbersToken' ''
if (-not $Token) { Fail "BuildNumbersToken is not set in $ConfigPath." }

# --- API helper ---------------------------------------------------------------
function Invoke-Numbers {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body
    )
    $params = @{
        Method  = $Method
        Uri     = "$BaseUrl$Path"
        Headers = @{ Authorization = "Bearer $Token" }
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Compress)
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
        Fail "$Method $Path failed: $detail"
    }
}

# --- Run the requested action --------------------------------------------------
switch ($PSCmdlet.ParameterSetName) {
    'List' {
        $response = Invoke-Numbers -Method GET -Path '/v1/tracks'
        $tracks = @($response.tracks)
        if ($tracks.Count -eq 0) {
            Info "No tracks on $BaseUrl yet."
            break
        }
        Info "Tracks on $BaseUrl :"
        foreach ($t in $tracks) {
            Write-Host ("  {0}  current={1}  next={2}" -f $t.track, $t.number, ($t.number + 1))
        }
    }
    'Get' {
        $t = Invoke-Numbers -Method GET -Path "/v1/tracks/$([uri]::EscapeDataString($Track))"
        Info ("Track {0}: current={1}, next allocation returns {2}." -f $t.track, $t.number, ($t.number + 1))
    }
    'Seed' {
        $t = Invoke-Numbers -Method PUT -Path "/v1/tracks/$([uri]::EscapeDataString($Track))" -Body @{ number = $Seed }
        Info ("Seeded track {0} at {1}; next allocation returns {2}." -f $t.track, $t.number, ($t.number + 1))
    }
    'Delete' {
        Invoke-Numbers -Method DELETE -Path "/v1/tracks/$([uri]::EscapeDataString($Track))" | Out-Null
        Info "Deleted track '$Track'."
    }
}
