#requires -Version 7
<#
.SYNOPSIS
    Deploy a freshly built Scissors paperclip jar to a Pelican-managed server:
    stop the server, upload the jar, and start it again.

.DESCRIPTION
    Reads settings from a gitignored config file (default: scripts/pelican.config.psd1).
    Copy scripts/pelican.config.example.psd1 to scripts/pelican.config.psd1 and fill it in.

    It drives the Pelican panel client API (https://pelican.dev / github.com/pelican-dev/panel):
      1. POST /api/client/servers/{id}/power   {"signal":"stop"}
      2. Poll GET .../resources until current_state is offline (kill on timeout if enabled)
      3. GET .../files/upload  -> signed node URL, then multipart POST the jar to the node
      4. POST .../power        {"signal":"start"}

    The jar is uploaded under RemoteName (default server.jar). That name MUST match the jar
    your server's startup command runs, because paperclip jar names are version-stamped and change.

.EXAMPLE
    ./scripts/deploy-pelican.ps1 -Build

.NOTES
    Run this yourself; it stops your live server. Requires PowerShell 7+ and curl.exe (bundled
    with Windows 10/11). Requests are pinned to IPv4 by default (ForceIpv4) because Pelican API
    keys are IP-allowlisted and a dual-stack host may otherwise be rejected for using IPv6.
#>
[CmdletBinding()]
param(
    # Path to the config file. Defaults to pelican.config.psd1 next to this script.
    [string]$ConfigPath,
    # Optional: override the jar chosen from config / auto-detection.
    [string]$JarPath,
    # Force a paperclip build before deploying (overrides config Build).
    [switch]$Build,
    # Force-kill if the graceful stop times out (overrides config KillOnTimeout).
    [switch]$KillOnTimeout
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

function Info([string]$message) {
    Write-Host $message -ForegroundColor Cyan
}

# --- Load config ------------------------------------------------------------
if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'pelican.config.psd1' }
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Fail "Config not found: $ConfigPath`n       Copy scripts/pelican.config.example.psd1 to scripts/pelican.config.psd1 and fill it in."
}
$cfg = Import-PowerShellDataFile -LiteralPath $ConfigPath

function Cfg([string]$key, $default) {
    if ($cfg.ContainsKey($key) -and $null -ne $cfg[$key] -and "$($cfg[$key])" -ne '') { return $cfg[$key] }
    return $default
}

$PanelUrl   = (Cfg 'PanelUrl'   '').ToString().TrimEnd('/')
$ApiKey     =  Cfg 'ApiKey'     ''
$ServerId   =  Cfg 'ServerId'   ''
$RemoteName =  Cfg 'RemoteName' 'server.jar'
$RemoteDir  =  Cfg 'RemoteDir'  '/'
$StopTimeoutSeconds  = [int](Cfg 'StopTimeoutSeconds'  120)
$StartTimeoutSeconds = [int](Cfg 'StartTimeoutSeconds' 120)
$ForceIpv4 = [bool](Cfg 'ForceIpv4' $true)
if (-not $JarPath)       { $JarPath = Cfg 'JarPath' '' }
if (-not $Build)         { $Build         = [bool](Cfg 'Build' $false) }
if (-not $KillOnTimeout) { $KillOnTimeout = [bool](Cfg 'KillOnTimeout' $false) }

# --- Validate configuration -------------------------------------------------
if (-not $PanelUrl) { Fail "PanelUrl is not set in $ConfigPath (e.g. https://panel.example.com)." }
if (-not $ApiKey)   { Fail "ApiKey is not set in $ConfigPath. Use a Client API key (Account -> API Credentials)." }
if (-not $ServerId) { Fail "ServerId is not set in $ConfigPath (the short id from the panel server URL)." }

$base = "$PanelUrl/api/client/servers/$ServerId"
# Requests go through curl.exe so the connection can be pinned to IPv4 (Invoke-RestMethod has no
# such option). Pelican API keys are IP-allowlisted, and a dual-stack host may otherwise egress
# over IPv6 and be rejected.
$curl = 'curl.exe'
# Keep $ipFlags an array. An if-expression return unwraps a one-element array to a scalar string,
# and `@ipFlags` would then splat that string character-by-character (feeding curl a bare '-').
$ipFlags = @()
if ($ForceIpv4) { $ipFlags = @('--ipv4') }

# --- Optionally build the paperclip jar -------------------------------------
if ($Build) {
    Info 'Building paperclip jar...'
    $gradle = Join-Path $repoRoot 'gradlew.bat'
    & $gradle ':scissors-server:createPaperclipJar'
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (exit $LASTEXITCODE)." }
}

# --- Resolve the jar to upload ----------------------------------------------
if (-not $JarPath) {
    $libs = Join-Path $repoRoot 'scissors-server/build/libs'
    $candidate = Get-ChildItem -Path $libs -Filter 'scissors-paperclip-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) {
        Fail "No scissors-paperclip-*.jar found in $libs. Build first with -Build or set JarPath in the config."
    }
    $JarPath = $candidate.FullName
}
if (-not (Test-Path -LiteralPath $JarPath)) { Fail "Jar not found: $JarPath" }
$jar = Get-Item -LiteralPath $JarPath
if ($jar.Name -notlike 'scissors-paperclip-*') {
    Write-Host "WARNING: $($jar.Name) is not a paperclip jar; the thin dev jar cannot run standalone." -ForegroundColor Yellow
}

Info "Panel:   $PanelUrl"
Info "Server:  $ServerId"
Info "Jar:     $($jar.FullName) ($([math]::Round($jar.Length / 1MB, 1)) MB)"
Info "Upload:  $RemoteDir$RemoteName"

# --- API helpers ------------------------------------------------------------
# Runs a panel API call via curl. The API key is passed through curl's stdin config (-K -) so it
# never appears in the process command line, while the URL and JSON body are ordinary arguments.
function Invoke-Panel {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Url,
        [string]$JsonBody
    )
    $conf = @(
        '--silent', '--show-error', '--fail-with-body'
        "header = `"Authorization: Bearer $ApiKey`""
        'header = "Accept: application/json"'
    )
    $cargs = @($ipFlags) + @('-K', '-', '-X', $Method)
    if ($JsonBody) {
        $conf += 'header = "Content-Type: application/json"'
        $cargs += @('--data-raw', $JsonBody)
    }
    $cargs += $Url
    $response = ($conf -join "`n") | & $curl @cargs 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "API call failed ($Method $Url):`n$(($response | Out-String).Trim())" }
    return ($response | Out-String)
}

function Send-Power([string]$signal) {
    Invoke-Panel -Method POST -Url "$base/power" -JsonBody (@{ signal = $signal } | ConvertTo-Json -Compress) | Out-Null
}

function Get-State {
    $json = Invoke-Panel -Method GET -Url "$base/resources" | ConvertFrom-Json
    return $json.attributes.current_state
}

function Wait-ForState([string[]]$targets, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $state = Get-State
        Write-Host "  state: $state"
        if ($targets -contains $state) { return $state }
        Start-Sleep -Seconds 3
    }
    return $null
}

# The REST /resources state is cached ~20s by the panel, so it lags a real stop/start. The daemon
# websocket pushes live state instead: it sends the current status right after auth, then every
# transition. Wait-ForStateLive uses it and falls back to polling if the socket can't be used.
function Get-WsEndpoint {
    $r = Invoke-Panel -Method GET -Url "$base/websocket" | ConvertFrom-Json
    return [pscustomobject]@{ Token = $r.data.token; Socket = $r.data.socket }
}

function Send-WsJson([System.Net.WebSockets.ClientWebSocket]$ws, [hashtable]$obj) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
    [void]$ws.SendAsync([System.ArraySegment[byte]]::new($bytes),
        [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

# Returns the reached state, or $null on timeout. Throws on connect/handshake failure so the caller
# can fall back to HTTP polling.
function Wait-ForStateWs([string[]]$targets, [int]$timeoutSeconds) {
    $ep = Get-WsEndpoint
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.SetRequestHeader('Origin', $PanelUrl)  # the daemon validates the Origin header
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $buffer = [byte[]]::new(16384)
    try {
        $ccts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(15))
        [void]$ws.ConnectAsync([Uri]$ep.Socket, $ccts.Token).GetAwaiter().GetResult()
        Send-WsJson $ws @{ event = 'auth'; args = @($ep.Token) }

        while ((Get-Date) -lt $deadline -and $ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $remaining = [Math]::Max(1, [int][Math]::Ceiling(($deadline - (Get-Date)).TotalSeconds))
            $rcts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds([Math]::Min(5, $remaining)))
            $ms = [System.IO.MemoryStream]::new()
            try {
                do {
                    $res = $ws.ReceiveAsync([System.ArraySegment[byte]]::new($buffer), $rcts.Token).GetAwaiter().GetResult()
                    if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { throw 'websocket closed by server' }
                    $ms.Write($buffer, 0, $res.Count)
                } while (-not $res.EndOfMessage)
            } catch [System.OperationCanceledException] {
                continue  # this receive timed out; loop re-checks the deadline
            }

            $evt = $null
            try { $evt = [System.Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json } catch { continue }
            switch ("$($evt.event)") {
                'status' {
                    $state = "$($evt.args[0])"
                    Write-Host "  state: $state"
                    if ($targets -contains $state) { return $state }
                }
                { $_ -in 'token expiring', 'token expired', 'jwt error' } {
                    Send-WsJson $ws @{ event = 'auth'; args = @((Get-WsEndpoint).Token) }  # re-auth with a fresh token
                }
            }
        }
        return $null
    } finally {
        try { $ws.Dispose() } catch {}
    }
}

function Wait-ForStateLive([string[]]$targets, [int]$timeoutSeconds) {
    try {
        return Wait-ForStateWs $targets $timeoutSeconds
    } catch {
        Write-Host "  (live state unavailable: $($_.Exception.Message); using polling)" -ForegroundColor DarkYellow
        return Wait-ForState $targets $timeoutSeconds
    }
}

# --- 1. Stop ----------------------------------------------------------------
Info "`nStopping server..."
Send-Power 'stop'
$stopped = Wait-ForStateLive @('offline', 'stopped') $StopTimeoutSeconds
if (-not $stopped) {
    if ($KillOnTimeout) {
        Write-Host "Stop timed out after $StopTimeoutSeconds s; sending kill." -ForegroundColor Yellow
        Send-Power 'kill'
        $stopped = Wait-ForState @('offline', 'stopped') 30
    }
    if (-not $stopped) {
        Fail 'Server did not reach offline within timeout. Aborting before upload (set KillOnTimeout to force).'
    }
}
Info 'Server is offline.'

# --- 2. Upload --------------------------------------------------------------
Info "`nRequesting upload URL..."
$signed = Invoke-Panel -Method GET -Url "$base/files/upload" | ConvertFrom-Json
$uploadUrl = $signed.attributes.url
if (-not $uploadUrl) { Fail 'Panel did not return an upload URL.' }
$uploadUri = "$uploadUrl&directory=$([uri]::EscapeDataString($RemoteDir))"

# The daemon (JWT in the URL, no API key) saves the file under the multipart filename, so stage a
# copy named exactly RemoteName. Staging in the temp dir also keeps the -F argument free of quotes.
$staged = Join-Path ([System.IO.Path]::GetTempPath()) $RemoteName
Copy-Item -LiteralPath $jar.FullName -Destination $staged -Force
try {
    Info "Uploading $RemoteName to the node..."
    $response = & $curl @ipFlags --silent --show-error --fail-with-body -F "files=@$staged" $uploadUri 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "Upload failed:`n$(($response | Out-String).Trim())" }
} finally {
    Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
}
Info 'Upload complete.'

# --- 3. Start ---------------------------------------------------------------
Info "`nStarting server..."
Send-Power 'start'
$running = Wait-ForStateLive @('running', 'starting') $StartTimeoutSeconds
if ($running) {
    Info "Server is $running."
} else {
    Write-Host 'Start signal sent, but server did not report running yet. Check the panel console.' -ForegroundColor Yellow
}

Info "`nDone: $($jar.Name) deployed to $ServerId as $RemoteName."
