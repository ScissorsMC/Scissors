@{
    # Copy this file to pelican.config.psd1 (gitignored) and fill in your values.

    # Base URL of your Pelican panel, no trailing slash.
    PanelUrl = 'https://panel.example.com'

    # A CLIENT API key from the panel: Account -> API Credentials -> Create.
    # This is a secret. It lives only in pelican.config.psd1, which is gitignored.
    ApiKey = 'plcn_your_client_api_key_here'

    # The short server id shown in the panel URL, e.g. /server/1a7ce997 -> '1a7ce997'.
    ServerId = '1a7ce997'

    # Remote filename to upload the jar as. MUST match the jar your server's startup
    # command runs (paperclip jar names are version-stamped and change every build).
    RemoteName = 'server.jar'

    # Remote directory to upload into, relative to the server root.
    RemoteDir = '/'

    # Optional: absolute path to a specific jar. Leave empty to auto-pick the newest
    # scissors-server/build/libs/scissors-paperclip-*.jar.
    JarPath = ''

    # Build the paperclip jar before deploying. Can also be forced with -Build.
    Build = $false

    # Force-kill the server if it does not stop gracefully within StopTimeoutSeconds.
    KillOnTimeout = $false

    # How long to wait for the server to stop / start before giving up (seconds).
    StopTimeoutSeconds  = 120
    StartTimeoutSeconds = 120

    # Pin API requests to IPv4. Pelican API keys are IP-allowlisted; a dual-stack host can
    # otherwise egress over IPv6 and be rejected. Set to $false to allow IPv6.
    ForceIpv4 = $true
}
