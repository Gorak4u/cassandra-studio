#!/bin/bash
# TEST-ENV STUB of the estate's incremental-backup-to-s3.sh (control repo:
# site-modules/cassandra_pfpt/files/incremental-backup-to-s3.sh): same path, CLI arguments
# (--local-only, --upload-only --tag, --throttle), config file, log lines, object layout and
# exit codes. Differences (sidecar without nodetool, openssl or root, data volume read-only):
# no root check, archives are plain tar.gz named .enc, DC/rack come from config.json
# "stub_nodes", and step 3 cannot delete the archived source files (it says so).
# Uses the estate's real backup-storage-lib.sh.

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

_SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
CONFIG_FILE="/etc/backup/config.json"
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="$(readlink -f "$_SCRIPT_DIR/../../../etc/backup/config.json" 2>/dev/null || echo "$CONFIG_FILE")"
_stub_log_path() { if mkdir -p "$(dirname "$1")" 2>/dev/null && touch "$1" 2>/dev/null; then echo "$1"; else echo "${TMPDIR:-/tmp}/$(basename "$1")"; fi; }
LOG_FILE=$(_stub_log_path "/var/log/cassandra/incremental_backup.log")

log_message() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}
log_info() { log_message "${BLUE}$1${NC}"; }
log_success() { log_message "${GREEN}$1${NC}"; }
log_warn() { log_message "${YELLOW}$1${NC}"; }
log_error() { log_message "${RED}$1${NC}"; }

usage() {
    log_message "${YELLOW}Usage: $0 [MODE] [OPTIONS]${NC}"
    log_message "Manages the incremental backup process with modular steps."
    log_message ""
    log_message "Modes (mutually exclusive):"
    log_message "  --local-only                Archives incremental files to a local directory but does not upload to S3 or clean up source files."
    log_message "  --upload-only               Uploads a previously created local backup set to S3 and cleans up. Requires --tag."
    log_message "  (no mode)                   Default. Performs all steps: local backup, upload, and cleanup."
    log_message ""
    log_message "Options:"
    log_message "  --tag <timestamp>           The timestamp tag (YYYY-MM-DD-HH-MM) of the backup set to upload. Required for --upload-only."
    log_message "  --throttle <rate>           Throttle S3 upload speed (e.g., 50M/s, 1G/s). Overrides the default from config."
    log_message "  -h, --help                  Show this help message."
}

MODE="default"
BACKUP_TAG_OVERRIDE=""
THROTTLE_OVERRIDE=""
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --local-only) MODE="local_only" ;;
        --upload-only) MODE="upload_only" ;;
        --tag) BACKUP_TAG_OVERRIDE="$2"; shift ;;
        --throttle) THROTTLE_OVERRIDE="$2"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) log_error "Unknown parameter passed: $1"; usage; exit 1 ;;
    esac
    shift
done
if [ "$MODE" = "upload_only" ] && [ -z "$BACKUP_TAG_OVERRIDE" ]; then
    log_error "--tag <timestamp> is required for --upload-only mode."
    usage
    exit 1
fi
if [ -n "$BACKUP_TAG_OVERRIDE" ] && ! [[ "$BACKUP_TAG_OVERRIDE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}(-[0-9]{2})?$ ]]; then
    log_error "Invalid --tag '$BACKUP_TAG_OVERRIDE'. Expected YYYY-MM-DD-HH-MM or YYYY-MM-DD-HH-MM-SS."
    exit 1
fi

for tool in jq tar find; do
    if ! command -v $tool &> /dev/null; then log_error "Required tool '$tool' is not installed or in PATH."; exit 1; fi
done
if [ ! -f "$CONFIG_FILE" ]; then log_error "Backup configuration file not found at $CONFIG_FILE"; exit 1; fi

STORAGE_LIB="$_SCRIPT_DIR/backup-storage-lib.sh"
if [ ! -f "$STORAGE_LIB" ]; then
    log_error "Storage library not found at $STORAGE_LIB"
    exit 1
fi
# shellcheck source=/dev/null
. "$STORAGE_LIB"

S3_BUCKET_NAME=$(jq -r '.s3_bucket_name' "$CONFIG_FILE")
BACKUP_BACKEND=$(jq -r '.backup_backend // "s3"' "$CONFIG_FILE")
CASSANDRA_DATA_DIR=$(jq -r '.cassandra_data_dir' "$CONFIG_FILE")
LOG_FILE=$(_stub_log_path "$(jq -r '.incremental_backup_log_file' "$CONFIG_FILE")")
LISTEN_ADDRESS=$(jq -r '.listen_address // ""' "$CONFIG_FILE")
THROTTLE_RATE_FROM_CONFIG=$(jq -r '.throttle_rate // ""' "$CONFIG_FILE")
THROTTLE_RATE="${THROTTLE_OVERRIDE:-$THROTTLE_RATE_FROM_CONFIG}"
[ "$THROTTLE_RATE" = "null" ] && THROTTLE_RATE=""

if [ -z "$S3_BUCKET_NAME" ] || [ -z "$CASSANDRA_DATA_DIR" ] || [ -z "$LOG_FILE" ]; then
  log_error "One or more required configuration values are missing from $CONFIG_FILE"
  exit 1
fi
if ! storage_init "$CONFIG_FILE"; then
    log_error "Could not initialise the '$BACKUP_BACKEND' storage backend. Aborting."
    exit 1
fi
storage_set_throttle "$THROTTLE_RATE"
storage_export_functions

HOSTNAME=$(hostname -s)
BACKUP_TAG=${BACKUP_TAG_OVERRIDE:-$(date +'%Y-%m-%d-%H-%M-%S')}
# STUB: the real script stages in /var/lib/cassandra/local_backups; the stub never writes to the
# node's data volume.
LOCAL_BACKUP_BASE_DIR="${TMPDIR:-/tmp}/local_backups"
LOCAL_BACKUP_DIR="$LOCAL_BACKUP_BASE_DIR/$BACKUP_TAG"
LOCK_FILE="${TMPDIR:-/tmp}/cassandra_backup.lock"
ERROR_DIR="$LOCAL_BACKUP_DIR/errors"

do_local_backup() {
    log_info "--- Step 1: Creating Local Incremental Backup ---"
    local INCREMENTAL_DIRS_COUNT
    INCREMENTAL_DIRS_COUNT=$(find "$CASSANDRA_DATA_DIR" -type d -name "backups" -not -empty -print | wc -l)
    if [ "$INCREMENTAL_DIRS_COUNT" -eq 0 ]; then
        log_info "No new incremental backup files found. Nothing to do."
        return 2
    fi
    mkdir -p "$LOCAL_BACKUP_DIR" || { log_error "Failed to create local backup directory."; return 1; }
    mkdir -p "$ERROR_DIR"
    TABLES_BACKED_UP="[]"
    INCLUDED_SYSTEM_KEYSPACES="system_schema system_auth system_distributed"
    log_info "Archiving incremental files from source directories..."
    while IFS= read -r -d $'\0' backup_dir; do
        local relative_path ks_name table_dir_name table_name
        relative_path=${backup_dir#"$CASSANDRA_DATA_DIR"/}
        ks_name=$(echo "$relative_path" | cut -d'/' -f1)
        local is_system_ks=false
        for included_ks in $INCLUDED_SYSTEM_KEYSPACES; do
            if [ "$ks_name" == "$included_ks" ]; then is_system_ks=true; break; fi
        done
        if [[ "$ks_name" == system* || "$ks_name" == dse* || "$ks_name" == solr* ]] && [ "$is_system_ks" = false ]; then
            continue
        fi
        table_dir_name=$(echo "$relative_path" | cut -d'/' -f2)
        table_name=$(echo "$table_dir_name" | rev | cut -d'-' -f2- | rev)
        log_info "Processing incremental backup for: $ks_name.$table_name"
        local local_tar_path="$LOCAL_BACKUP_DIR/$ks_name"
        mkdir -p "$local_tar_path"
        local file_list="$local_tar_path/$table_name.filelist"
        ( cd "$backup_dir" && find . -maxdepth 1 -type f -printf '%P\n' 2>/dev/null || true ) > "$file_list"
        if [ ! -s "$file_list" ]; then
            log_info "No incremental files for $ks_name.$table_name; skipping."
            rm -f "$file_list"
            continue
        fi
        if ! tar -C "$backup_dir" -czf "$local_tar_path/$table_name.tar.gz.enc" -T "$file_list"; then
            log_error "Failed to archive incremental backup for $ks_name.$table_name."
            touch "$ERROR_DIR/$ks_name.$table_name"
            continue
        fi
        TABLES_BACKED_UP=$(echo "$TABLES_BACKED_UP" | jq ". + [\"$ks_name/$table_name\"]")
    done < <(find "$CASSANDRA_DATA_DIR" -type d -name "backups" -not -empty -print0)

    log_info "Creating backup manifest..."
    local NODE_DC NODE_RACK
    NODE_DC=$(jq -r --arg h "$HOSTNAME" '.stub_nodes[$h].datacenter // "Unknown"' "$CONFIG_FILE")
    NODE_RACK=$(jq -r --arg h "$HOSTNAME" '.stub_nodes[$h].rack // "Unknown"' "$CONFIG_FILE")
    [ -z "$LISTEN_ADDRESS" ] || [ "$LISTEN_ADDRESS" == "null" ] && LISTEN_ADDRESS="$(hostname -i | awk '{print $1}')"
    jq -n \
      --arg backup_id "$BACKUP_TAG" \
      --arg timestamp "$(date --iso-8601=seconds)" \
      --arg node_ip "$LISTEN_ADDRESS" \
      --arg node_dc "$NODE_DC" \
      --arg node_rack "$NODE_RACK" \
      --argjson tables "$TABLES_BACKED_UP" \
      '{
        "backup_id": $backup_id,
        "backup_type": "incremental",
        "timestamp_utc": $timestamp,
        "source_node": {
          "ip_address": $node_ip,
          "datacenter": $node_dc,
          "rack": $node_rack
        },
        "tables_backed_up": $tables
      }' > "$LOCAL_BACKUP_DIR/backup_manifest.json"
    log_success "--- Local Backup Finished. Stored at $LOCAL_BACKUP_DIR ---"
    return 0
}

do_upload() {
    log_info "--- Step 2: Uploading Local Backup to S3 ---"
    if [ ! -d "$LOCAL_BACKUP_DIR" ]; then log_error "Local backup directory not found: $LOCAL_BACKUP_DIR"; return 1; fi
    log_info "Uploading all backup files to $(storage_uri "$HOSTNAME/$BACKUP_TAG")..."
    if ! storage_upload_tree "$LOCAL_BACKUP_DIR" "$HOSTNAME/$BACKUP_TAG/" "*.filelist"; then
        log_error "Recursive upload failed. Upload is incomplete."
        return 1
    fi
    log_success "--- Upload Finished Successfully ---"
    return 0
}

do_source_and_local_cleanup() {
    log_info "--- Step 3: Cleaning Up Source & Local Files ---"
    # STUB: the data volume is read-only here, so archived source files stay in place.
    log_warn "Source incremental files are on a read-only volume in this test environment; leaving them in place."
    log_info "Removing local backup staging directory: $LOCAL_BACKUP_DIR"
    rm -rf "$LOCAL_BACKUP_DIR"
    log_success "--- Cleanup Finished ---"
}

if [ -f "$LOCK_FILE" ]; then
    OLD_PID=$(cat "$LOCK_FILE")
    if [ -d "/proc/$OLD_PID" ]; then log_warn "Backup process with PID $OLD_PID is still running. Exiting."; exit 1; fi
    log_warn "Stale lock file found for dead PID $OLD_PID. Removing."; rm -f "$LOCK_FILE"
fi
echo $$ > "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"' EXIT

log_info "--- Starting Incremental Backup Manager (Mode: $MODE) ---"
if [ -n "$THROTTLE_RATE" ]; then
    log_info "Bandwidth Throttling: $THROTTLE_RATE"
fi

run_local_backup() {
    local rc=0
    do_local_backup || rc=$?
    case "$rc" in
        0) return 0 ;;
        2) log_info "No new incremental files to archive; nothing to do."; exit 0 ;;
        *) log_error "Local backup creation failed (rc=$rc). Aborting."; exit 1 ;;
    esac
}

run_upload_and_cleanup() {
    local rc=0
    do_upload || rc=$?
    case "$rc" in
        0) do_source_and_local_cleanup ;;
        *) log_error "Upload failed (rc=$rc). Local backup and original incremental files are preserved for retry."; exit 1 ;;
    esac
}

case "$MODE" in
    "local_only")
        run_local_backup
        log_info "Local incremental backup set created at $LOCAL_BACKUP_DIR. Source files and upload skipped."
        ;;
    "upload_only") run_upload_and_cleanup ;;
    "default")
        run_local_backup
        run_upload_and_cleanup
        ;;
esac

log_info "--- Incremental Backup Process Finished ---"
exit 0
