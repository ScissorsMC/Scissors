@{
    # Copy this file to fill.config.psd1 (gitignored) and fill it in.

    # Public base URL of the Fill API, no trailing slash.
    ApiUrl = 'https://fill.scissors.gg'

    # Fill project key.
    ProjectKey = 'scissors'

    # Fill admin credentials (a user with the API_MANAGE role in application.yaml).
    # Used by fill-family.ps1 and fill-support.ps1 for the GraphQL management API.
    # This is a secret. It lives only in fill.config.psd1, which is gitignored.
    AdminUser = 'admin'
    AdminPassword = ''

    # Build number allocation service (https://github.com/ScissorsMC/build-numbers).
    # Used by build-numbers.ps1, and by fill-status.ps1 to compare each
    # version's track against its latest published build when the token is set.
    BuildNumbersUrl = 'https://numbers.scissors.gg'

    # The worker's AUTH_TOKEN (same value as the BUILD_NUMBER_TOKEN GitHub
    # secret). This is a secret. It lives only in fill.config.psd1, which is
    # gitignored.
    BuildNumbersToken = ''

    # Track names are '<prefix>-<version>', e.g. 'scissors-26.2'. Defaults to
    # ProjectKey; set this only if the prefix differs from the project key.
    # TrackPrefix = 'scissors'
}
