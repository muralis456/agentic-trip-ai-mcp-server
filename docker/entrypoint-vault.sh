#!/bin/sh
set -eu

VAULT_ROLE_ID_FILE="/run/secrets/vault_role_id"
VAULT_SECRET_ID_FILE="/run/secrets/vault_secret_id"

if [ ! -r "$VAULT_ROLE_ID_FILE" ]; then
  echo "ERROR: Vault Role ID secret is missing" >&2
  exit 1
fi

if [ ! -r "$VAULT_SECRET_ID_FILE" ]; then
  echo "ERROR: Vault Secret ID secret is missing" >&2
  exit 1
fi

export VAULT_ROLE_ID="$(tr -d '\r\n' < "$VAULT_ROLE_ID_FILE")"
export VAULT_SECRET_ID="$(tr -d '\r\n' < "$VAULT_SECRET_ID_FILE")"

if [ -z "$VAULT_ROLE_ID" ] || [ -z "$VAULT_SECRET_ID" ]; then
  echo "ERROR: Vault AppRole credentials are empty" >&2
  exit 1
fi

exec java -jar /app/app.jar --spring.profiles.active=docker
