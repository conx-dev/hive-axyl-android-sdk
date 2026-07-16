#!/usr/bin/env bash

set -euo pipefail

requireVariable() {
  local variable_name="$1"
  local variable_value="${!variable_name:-}"
  if [[ -z "$variable_value" ]]; then
    echo "Required environment variable is missing: $variable_name" >&2
    exit 1
  fi
}

requireCommand() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command is missing: $command_name" >&2
    exit 1
  fi
}

writeFailure() {
  local response_file="$1"
  if jq -e . "$response_file" >/dev/null 2>&1; then
    jq -c '{deploymentId, deploymentState, errors, message}' "$response_file" >&2
    return
  fi
  sed -n '1,20p' "$response_file" >&2
}

requireVariable MAVEN_CENTRAL_USERNAME
requireVariable MAVEN_CENTRAL_PASSWORD
requireVariable MAVEN_CENTRAL_BUNDLE
requireVariable MAVEN_CENTRAL_DEPLOYMENT_NAME
requireCommand base64
requireCommand curl
requireCommand jq

if [[ ! -f "$MAVEN_CENTRAL_BUNDLE" ]]; then
  echo "Maven Central bundle was not found: $MAVEN_CENTRAL_BUNDLE" >&2
  exit 1
fi

if [[ ! "$MAVEN_CENTRAL_DEPLOYMENT_NAME" =~ ^[0-9A-Za-z._-]+$ ]]; then
  echo "Maven Central deployment name is invalid" >&2
  exit 1
fi

auth_file="$(mktemp)"
response_file="$(mktemp)"
trap 'rm -f "$auth_file" "$response_file"' EXIT
chmod 600 "$auth_file" "$response_file"

bearer_token="$(
  printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" \
    | base64 \
    | tr -d '\r\n'
)"
printf 'header = "Authorization: Bearer %s"\n' "$bearer_token" > "$auth_file"
unset bearer_token

upload_url="https://central.sonatype.com/api/v1/publisher/upload"
upload_url+="?name=$MAVEN_CENTRAL_DEPLOYMENT_NAME"
upload_url+="&publishingType=AUTOMATIC"

upload_status="$(
  curl \
    --config "$auth_file" \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 120 \
    --output "$response_file" \
    --write-out '%{http_code}' \
    --request POST \
    --form "bundle=@${MAVEN_CENTRAL_BUNDLE};type=application/octet-stream" \
    "$upload_url"
)"

if [[ "$upload_status" != "201" ]]; then
  echo "Maven Central bundle upload failed with HTTP $upload_status" >&2
  writeFailure "$response_file"
  exit 1
fi

deployment_id="$(tr -d '\r\n' < "$response_file")"
if [[ ! "$deployment_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "Maven Central returned an invalid deployment ID" >&2
  exit 1
fi

echo "Maven Central deployment created: $deployment_id"

deadline=$((SECONDS + 2400))
status_url="https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id"

while ((SECONDS < deadline)); do
  status_code="$(
    curl \
      --config "$auth_file" \
      --silent \
      --show-error \
      --retry 3 \
      --retry-all-errors \
      --retry-delay 2 \
      --connect-timeout 10 \
      --max-time 60 \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --request POST \
      "$status_url"
  )"

  if [[ "$status_code" != "200" ]]; then
    echo "Maven Central status request failed with HTTP $status_code" >&2
    writeFailure "$response_file"
    exit 1
  fi

  if ! deployment_state="$(jq -er '.deploymentState // empty' "$response_file")"; then
    echo "Maven Central returned an invalid status response" >&2
    writeFailure "$response_file"
    exit 1
  fi
  echo "Maven Central deployment state: $deployment_state"

  case "$deployment_state" in
    PUBLISHED)
      exit 0
      ;;
    FAILED)
      writeFailure "$response_file"
      exit 1
      ;;
    PENDING | VALIDATING | VALIDATED | PUBLISHING)
      sleep 15
      ;;
    *)
      echo "Unexpected Maven Central deployment state: $deployment_state" >&2
      exit 1
      ;;
  esac
done

echo "Maven Central publication timed out for deployment $deployment_id" >&2
exit 1
