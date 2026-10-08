# 3. Secrets live outside the connection config

Status: accepted (2026-10-08)

## Decision
Connection configs hold no secrets. Passwords and passphrases go to the OS keychain, or to an
AES-GCM encrypted file (key file readable only by the owner) when no keychain works. The API
never returns secrets, only which ones are set. Exports therefore cannot leak secrets.
Studio Server will plug Vault or a cloud secret manager into the same `SecretStore` interface.
