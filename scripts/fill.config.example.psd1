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
}
