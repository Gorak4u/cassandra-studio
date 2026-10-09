#!/bin/bash
# TEST-ENV STUB of the estate's restore-from-s3.sh (control repo:
# site-modules/cassandra_pfpt/files/restore-from-s3.sh). Only the read-only --list-backups mode
# is implemented, with the real script's output format; every restore mode refuses to run
# (the sidecar has no sstableloader and a read-only data volume).

set -euo pipefail

RED='\e[0;31m'
GREEN='\e[0;32m'
YELLOW='\e[1;33m'
BLUE='\e[0;34m'
CYAN='\e[0;36m'
BOLD='\e[1m'
NC='\e[0m' # No Color

_SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
CONFIG_FILE="/etc/backup/config.json"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="$(readlink -f "$_SCRIPT_DIR/../../../etc/backup/config.json" 2>/dev/null || echo "$CONFIG_FILE")"
RESTORE_LOG_FILE="/var/log/cassandra/restore.log"
{ mkdir -p "$(dirname "$RESTORE_LOG_FILE")" && touch "$RESTORE_LOG_FILE"; } 2>/dev/null || RESTORE_LOG_FILE="${TMPDIR:-/tmp}/restore.log"
HOSTNAME=$(hostname -s)

log_message() {
    echo -e "[$(date +'%Y-%m-%d %H:%M:%S')] $1" | tee -a "$RESTORE_LOG_FILE"
}
log_info() { log_message "${BLUE}$1${NC}"; }
log_success() { log_message "${GREEN}$1${NC}"; }
log_warn() { log_message "${YELLOW}$1${NC}"; }
log_error() { log_message "${RED}$1${NC}"; }

usage() {
    cat >&2 <<USAGE
Usage: $0 [MODE] [OPTIONS]
  --list-backups                List all available backup sets for a host.
  --source-host <hostname>      Specify the source host for the backup. Defaults to the current hostname.
  --s3-bucket <name>            Override the S3 bucket from config.json.
(test-env stub: restore modes are not available)
USAGE
    exit 1
}

if [ ! -f "$CONFIG_FILE" ]; then
    log_error "Backup configuration file not found at $CONFIG_FILE"
    exit 1
fi
# shellcheck source=/dev/null
. "$_SCRIPT_DIR/backup-storage-lib.sh"

MODE=""
SOURCE_HOST_OVERRIDE=""
S3_BUCKET_OVERRIDE=""
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --list-backups) MODE="list" ;;
        --source-host) SOURCE_HOST_OVERRIDE="$2"; shift ;;
        --s3-bucket) S3_BUCKET_OVERRIDE="$2"; shift ;;
        --show-restore-chain|--full-restore|--download-only|--download-and-restore|--schema-only)
            log_error "$1 is not available in the test-env stub."; exit 1 ;;
        --date|--keyspace|--table|--throttle) shift ;;
        --yes) ;;
        *) log_error "Unknown parameter passed: $1"; usage ;;
    esac
    shift
done
if [ -z "$MODE" ]; then
    log_error "No mode specified. You must choose one of: --list-backups, --show-restore-chain, --full-restore, --schema-only, --download-only, --download-and-restore"
    usage
fi

S3_BUCKET_NAME=$(jq -r '.s3_bucket_name' "$CONFIG_FILE")
EFFECTIVE_S3_BUCKET=${S3_BUCKET_OVERRIDE:-$S3_BUCKET_NAME}
EFFECTIVE_SOURCE_HOST=${SOURCE_HOST_OVERRIDE:-$HOSTNAME}
if ! storage_init "$CONFIG_FILE"; then
    log_error "Could not initialise the storage backend. Aborting."
    exit 1
fi
storage_set_container "$EFFECTIVE_S3_BUCKET"

log_info "--- Starting Point-in-Time Restore Manager ---"
log_info "Mode: $MODE"
log_info "Target S3 Bucket: $EFFECTIVE_S3_BUCKET"
log_info "Source Hostname for Restore: $EFFECTIVE_SOURCE_HOST"

log_info "--- Listing Available Backups for Host: $EFFECTIVE_SOURCE_HOST ---"
all_backups=$(storage_list_prefixes "$EFFECTIVE_SOURCE_HOST" || echo "")
if [ -z "$all_backups" ]; then
    log_warn "No backups found for host '$EFFECTIVE_SOURCE_HOST' in bucket '$EFFECTIVE_S3_BUCKET'."
    exit 0
fi
printf "\n"
printf "%b\n" "${BOLD}${GREEN}Host: ${EFFECTIVE_SOURCE_HOST}${NC}"
printf "%b\n" "${YELLOW}----------------------------${NC}"
backups_to_sort=()
while IFS= read -r backup_ts; do
    if [[ ! "$backup_ts" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}(-[0-9]{2})?$ ]]; then
        continue
    fi
    manifest=$(storage_download_stream "$EFFECTIVE_SOURCE_HOST/$backup_ts/backup_manifest.json" || echo "{}")
    backup_type=$(echo "$manifest" | jq -r '.backup_type // "unknown"' 2>/dev/null || echo unknown)
    type_color=$NC
    if [ "$backup_type" == "full" ]; then type_color=${CYAN}; elif [ "$backup_type" == "incremental" ]; then type_color=${BLUE}; fi
    formatted_line="  - ${BOLD}${backup_ts}${NC} (type: ${type_color}${backup_type}${NC})"
    backups_to_sort+=("$(printf "${backup_ts}\t${formatted_line}")")
done <<< "$all_backups"
if [ ${#backups_to_sort[@]} -eq 0 ]; then
    log_warn "No valid backup sets found to list."
    exit 0
fi
printf "%s\n" "${backups_to_sort[@]}" | sort | cut -d$'\t' -f2- | while IFS= read -r line; do
    printf "%b\n" "$line"
done
printf "\n"
exit 0
