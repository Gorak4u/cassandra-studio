#!/bin/bash
# TEST-ENV STUB of the estate's full-backup-to-s3.sh (control repo:
# site-modules/cassandra_pfpt/files/full-backup-to-s3.sh), installed at the same path
# (/usr/local/bin) with the same CLI arguments, config file (/etc/backup/config.json), log
# lines, object layout (<host>/<tag>/<ks>/<table>.tar.gz.enc, backup_manifest.json,
# schema_mapping.json) and exit codes, so Cassandra Studio drives it exactly like the real one.
#
# What differs, because the sshd sidecar has no nodetool, cqlsh, openssl or root and sees
# /var/lib/cassandra read-only:
#   - no root check; the "snapshot" copies each table's live SSTables into a staging dir
#   - archives are plain tar.gz (not encrypted) but keep the .enc name
#   - cluster name, DC, rack and tokens come from config.json "stub_*" keys, not nodetool
#   - the schema dump fails with the real script's warning (no cqlsh)
#   - optional "stub_delay_per_table_sec" slows uploads so progress and cancel can be seen
# It uses the estate's real backup-storage-lib.sh (local backend).

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

_SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
CONFIG_FILE="/etc/backup/config.json"
# STUB: also runs from a user's home (…/usr/local/bin next to …/etc/backup) without mounts.
[ -f "$CONFIG_FILE" ] || CONFIG_FILE="$(readlink -f "$_SCRIPT_DIR/../../../etc/backup/config.json" 2>/dev/null || echo "$CONFIG_FILE")"
# STUB: fall back to $TMPDIR when the log directory is not writable.
_stub_log_path() { if mkdir -p "$(dirname "$1")" 2>/dev/null && touch "$1" 2>/dev/null; then echo "$1"; else echo "${TMPDIR:-/tmp}/$(basename "$1")"; fi; }
LOG_FILE=$(_stub_log_path "/var/log/cassandra/full_backup.log")

log_message() {
  echo -e "[$(date +'%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}
log_info() { log_message "${BLUE}$1${NC}"; }
log_success() { log_message "${GREEN}$1${NC}"; }
log_warn() { log_message "${YELLOW}$1${NC}"; }
log_error() { log_message "${RED}$1${NC}"; }

usage() {
    log_message "${YELLOW}Usage: $0 [OPTIONS]${NC}"
    log_message "Performs a full, node-local snapshot and uploads all table data to S3."
    log_message ""
    log_message "Options:"
    log_message "  --throttle <rate>    Throttle S3 upload speed (e.g., '50M/s', '1G/s')."
    log_message "                       This overrides the 'throttle_rate' from config.json for this run."
    log_message "  -h, --help           Show this help message."
    exit 0
}

for tool in jq tar gzip xargs find; do
    if ! command -v $tool &> /dev/null; then
        log_error "Required tool '$tool' is not installed or in PATH."
        exit 1
    fi
done

if [ ! -f "$CONFIG_FILE" ]; then
  log_error "Backup configuration file not found at $CONFIG_FILE"
  exit 1
fi

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
LOG_FILE=$(_stub_log_path "$(jq -r '.full_backup_log_file' "$CONFIG_FILE")")
LISTEN_ADDRESS=$(jq -r '.listen_address // ""' "$CONFIG_FILE")
KEEP_DAYS=$(jq -r '.clearsnapshot_keep_days // 0' "$CONFIG_FILE")
UPLOAD_STREAMING=$(jq -r '.upload_streaming // "false"' "$CONFIG_FILE")
PARALLELISM=$(jq -r '.parallelism // 4' "$CONFIG_FILE")
S3_RETENTION_PERIOD=$(jq -r '.s3_retention_period // 0' "$CONFIG_FILE")
S3_OBJECT_LOCK_ENABLED=$(jq -r '.s3_object_lock_enabled // "false"' "$CONFIG_FILE")
STUB_DELAY=$(jq -r '.stub_delay_per_table_sec // 0' "$CONFIG_FILE")

THROTTLE_OVERRIDE=""
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --throttle) THROTTLE_OVERRIDE="$2"; shift ;;
        -h|--help) usage ;;
        *) log_error "Unknown parameter passed: $1"; usage; exit 1 ;;
    esac
    shift
done
if [ -n "$THROTTLE_OVERRIDE" ]; then
    THROTTLE_RATE="$THROTTLE_OVERRIDE"
else
    THROTTLE_RATE=$(jq -r '.throttle_rate // ""' "$CONFIG_FILE")
fi
[ "$THROTTLE_RATE" = "null" ] && THROTTLE_RATE=""

for _cfg in S3_BUCKET_NAME CASSANDRA_DATA_DIR LOG_FILE; do
  _val="${!_cfg}"
  if [ -z "$_val" ] || [ "$_val" == "null" ]; then
    log_error "Required configuration value '$_cfg' is missing or null in $CONFIG_FILE"
    exit 1
  fi
done
unset _cfg _val

if [ ! -d "$CASSANDRA_DATA_DIR" ]; then
  log_error "cassandra_data_dir '$CASSANDRA_DATA_DIR' does not exist. Refusing to produce an empty backup."
  exit 1
fi

if ! storage_init "$CONFIG_FILE"; then
    log_error "Could not initialise the '$BACKUP_BACKEND' storage backend. Aborting."
    exit 1
fi
storage_set_throttle "$THROTTLE_RATE"
storage_export_functions

BACKUP_TAG=$(date +'%Y-%m-%d-%H-%M-%S')
HOSTNAME=$(hostname -s)
# STUB: the real script stages in "${CASSANDRA_DATA_DIR%/*}/backup_temp_$$"; the stub never writes
# to the node's data volume (it is shared with the running Cassandra).
BACKUP_TEMP_DIR="${TMPDIR:-/tmp}/backup_temp_$$"
LOCK_FILE="${TMPDIR:-/tmp}/cassandra_backup.lock"
ERROR_DIR="$BACKUP_TEMP_DIR/errors"
SUCCESS_DIR="$BACKUP_TEMP_DIR/uploaded"
STAGE_DIR="$BACKUP_TEMP_DIR/snapshot"

log_info "Verifying ${BACKUP_BACKEND} credentials..."
if ! storage_check_credentials; then
    log_error "${BACKUP_BACKEND} credentials not found or invalid."
    log_error "Aborting backup before taking a snapshot to prevent wasted effort."
    exit 1
fi
log_success "${BACKUP_BACKEND} credentials are valid."

log_info "Checking if container '$(storage_uri "")' exists..."
if storage_container_exists; then
    log_success "Container already exists."
else
    log_warn "Container '$S3_BUCKET_NAME' does not exist. Attempting to create it..."
    mkdir -p "$(storage_uri "")" || { log_error "Failed to create container '$S3_BUCKET_NAME'."; exit 1; }
    log_success "Successfully created container '$S3_BUCKET_NAME'."
fi
if [ "$S3_OBJECT_LOCK_ENABLED" = "true" ]; then
    log_info "Verifying immutability (Object Lock) configuration for existing container..."
    log_warn "Object Lock is enabled in config, but container '$S3_BUCKET_NAME' does not support or have it enabled."
    log_warn "Backup will proceed WITHOUT applying retention to uploads."
fi
if [[ "$S3_RETENTION_PERIOD" =~ ^[1-9][0-9]*$ ]]; then
    log_info "Applying lifecycle expiry of $S3_RETENTION_PERIOD days to '$S3_BUCKET_NAME'..."
    log_success "Lifecycle policy applied."
else
    log_info "Storage lifecycle management skipped: retention period is not a positive number."
fi

if [ -f "$LOCK_FILE" ]; then
    log_warn "Lock file $LOCK_FILE exists. Checking if process is running..."
    OLD_PID=$(cat "$LOCK_FILE")
    if [ -d "/proc/$OLD_PID" ]; then
        log_warn "Backup process with PID $OLD_PID is still running. Exiting."
        exit 1
    else
        log_warn "Stale lock file found for dead PID $OLD_PID. Removing."
        rm -f "$LOCK_FILE"
    fi
fi
echo $$ > "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"; [ -d "$BACKUP_TEMP_DIR" ] && log_info "Cleaning up temporary directory: $BACKUP_TEMP_DIR"; rm -rf "$BACKUP_TEMP_DIR"' EXIT

if ! [[ "$KEEP_DAYS" =~ ^[0-9]+$ ]] || [ "$KEEP_DAYS" -le 0 ]; then
    log_info "Snapshot retention is not configured to a positive number ($KEEP_DAYS). Skipping old snapshot cleanup."
else
    log_info "--- Starting Old Snapshot Cleanup (via direct file deletion) ---"
    log_info "Retention period: $KEEP_DAYS days"
    log_info "No snapshot directories found on filesystem. Nothing to clean up."
    log_info "--- Snapshot Cleanup Finished ---"
fi

log_info "--- Starting Granular Cassandra Snapshot Backup Process ---"
log_info "S3 Bucket: $S3_BUCKET_NAME"
log_info "Backup Timestamp (Tag): $BACKUP_TAG"
log_info "Streaming Mode: $UPLOAD_STREAMING"
log_info "Parallelism: $PARALLELISM"
if [ -n "$THROTTLE_RATE" ]; then
    log_info "Bandwidth Throttling: $THROTTLE_RATE"
fi

mkdir -p "$BACKUP_TEMP_DIR" || { log_error "Failed to create temp backup directory on data volume."; exit 1; }
mkdir -p "$ERROR_DIR" "$SUCCESS_DIR" "$STAGE_DIR"

# 2. "Snapshot": the live SSTables of every table, copied to a staging tree <ks>/<table-dir>.
# A dropped and re-created table leaves old <table>-<id> directories behind; nodetool snapshot
# only covers the live one, so the stub takes the newest directory per keyspace/table name.
log_info "Taking full snapshot with tag: $BACKUP_TAG..."
declare -A _seen=()
while IFS= read -r table_path; do
    _ks=$(basename "$(dirname "$table_path")")
    _tbl=$(basename "$table_path" | rev | cut -d'-' -f2- | rev)
    [ -n "${_seen[$_ks.$_tbl]:-}" ] && continue
    _seen[$_ks.$_tbl]=1
    rel=${table_path#"$CASSANDRA_DATA_DIR/"}
    mkdir -p "$STAGE_DIR/$rel"
    find "$table_path" -maxdepth 1 -type f -exec cp -p {} "$STAGE_DIR/$rel/" \; 2>/dev/null || true
    (cd "$STAGE_DIR/$rel" && find . -maxdepth 1 -type f -printf '%P\n' | jq -R . | jq -sc '{files: .}') > "$STAGE_DIR/$rel/manifest.json"
done < <(find "$CASSANDRA_DATA_DIR" -maxdepth 2 -mindepth 2 -type d -printf '%T@ %p\n' | sort -rn | cut -d' ' -f2-)
log_success "Full snapshot taken successfully."

log_info "Discovering keyspaces and tables to back up..."
INCLUDED_SYSTEM_KEYSPACES="system_schema system_auth system_distributed"
SCHEMA_MAP_FILE="$BACKUP_TEMP_DIR/schema_mapping.json"
SCHEMA_MAP="{}"
while IFS= read -r table_path; do
    ks_name_map=$(basename "$(dirname "$table_path")")
    table_dir_name_map=$(basename "$table_path")
    table_name_map=$(echo "$table_dir_name_map" | rev | cut -d'-' -f2- | rev)
    is_system_ks_to_skip=true
    for included_ks_map in $INCLUDED_SYSTEM_KEYSPACES; do
        if [ "$ks_name_map" == "$included_ks_map" ]; then is_system_ks_to_skip=false; break; fi
    done
    if [[ "$ks_name_map" == system* || "$ks_name_map" == dse* || "$ks_name_map" == solr* ]] && [ "$is_system_ks_to_skip" = true ]; then continue; fi
    SCHEMA_MAP=$(echo "$SCHEMA_MAP" | jq --arg key "${ks_name_map}.${table_name_map}" --arg val "$table_dir_name_map" '. + {($key): $val}')
done < <(find "$CASSANDRA_DATA_DIR" -maxdepth 2 -mindepth 2 -type d -not -path '*/snapshots' -not -path '*/backups')
echo "$SCHEMA_MAP" > "$SCHEMA_MAP_FILE"
log_info "Schema-to-directory mapping generated."

process_table_backup() {
    local snapshot_dir="$1"
    local path_without_prefix=${snapshot_dir#"$STAGE_DIR/"}
    local ks_name table_dir_name table_name
    ks_name=$(echo "$path_without_prefix" | cut -d'/' -f1)
    table_dir_name=$(echo "$path_without_prefix" | cut -d'/' -f2)
    table_name=$(echo "$table_dir_name" | rev | cut -d'-' -f2- | rev)
    local is_system_ks=false
    for included_ks in $INCLUDED_SYSTEM_KEYSPACES; do
        if [ "$ks_name" == "$included_ks" ]; then is_system_ks=true; break; fi
    done
    if [[ "$ks_name" == system* || "$ks_name" == dse* || "$ks_name" == solr* ]] && [ "$is_system_ks" = false ]; then
        return 0
    fi
    log_info "Backing up table: $ks_name.$table_name"
    local object_key="$HOSTNAME/$BACKUP_TAG/$ks_name/$table_name.tar.gz.enc"
    local local_enc_file="$BACKUP_TEMP_DIR/$ks_name.$table_name.tar.gz.enc"
    if ! tar -C "$snapshot_dir" -czf "$local_enc_file" .; then
        log_error "Failed to archive $ks_name.$table_name. Skipping."
        touch "$ERROR_DIR/$ks_name.$table_name"
        return 1
    fi
    [ "$STUB_DELAY" != "0" ] && sleep "$STUB_DELAY"
    if ! bash -c 'storage_upload_file "$1" "$2"' _ "$local_enc_file" "$object_key"; then
        log_error "Failed to upload backup for $ks_name.$table_name to $(storage_uri "$object_key")"
        touch "$ERROR_DIR/$ks_name.$table_name"
    else
        touch "$SUCCESS_DIR/$ks_name.$table_name"
        log_success "Successfully uploaded backup for $ks_name.$table_name"
    fi
    rm -f "$local_enc_file"
}
export -f process_table_backup log_message log_info log_success log_warn log_error
export LOG_FILE BACKUP_TEMP_DIR STAGE_DIR INCLUDED_SYSTEM_KEYSPACES HOSTNAME BACKUP_TAG ERROR_DIR SUCCESS_DIR STUB_DELAY
export RED GREEN YELLOW BLUE NC

log_info "--- Pre-scanning for keyspaces to skip ---"
find "$CASSANDRA_DATA_DIR" -mindepth 1 -maxdepth 1 -type d | sort | while read -r ks_path; do
    ks_name=$(basename "$ks_path")
    is_system_ks=false
    for included_ks in $INCLUDED_SYSTEM_KEYSPACES; do
        if [ "$ks_name" == "$included_ks" ]; then is_system_ks=true; break; fi
    done
    if [[ "$ks_name" == system* || "$ks_name" == dse* || "$ks_name" == solr* ]] && [ "$is_system_ks" = false ]; then
        log_info "Skipping non-essential system keyspace backup: $ks_name"
    fi
done

log_info "--- Starting Parallel Backup of Tables ---"
find "$STAGE_DIR" -mindepth 2 -maxdepth 2 -type d -not -empty -print0 | \
    xargs -0 -P "$PARALLELISM" -I {} bash -c 'process_table_backup "$1"' _ {} || true
log_info "--- Finished Parallel Backup of Tables ---"

SNAPSHOT_DIRS_FOUND=$(find "$STAGE_DIR" -mindepth 2 -maxdepth 2 -type d -not -empty | wc -l | tr -d ' ')
UPLOAD_ERRORS=$(find "$ERROR_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
TABLES_BACKED_UP_SUCCESS_COUNT=$(find "$SUCCESS_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
TOTAL_TABLES_ATTEMPTED=$((TABLES_BACKED_UP_SUCCESS_COUNT + UPLOAD_ERRORS))
TABLES_SKIPPED=$((SNAPSHOT_DIRS_FOUND - TOTAL_TABLES_ATTEMPTED))

if [ "$SNAPSHOT_DIRS_FOUND" -eq 0 ]; then
    log_error "No table snapshots were found under $CASSANDRA_DATA_DIR for tag $BACKUP_TAG."
    log_error "Refusing to publish an empty backup that restore would later accept as a base."
    exit 1
fi
if [ "$TABLES_BACKED_UP_SUCCESS_COUNT" -eq 0 ]; then
    log_error "No tables were successfully backed up out of $SNAPSHOT_DIRS_FOUND snapshot(s) found."
    log_error "Check the include/exclude filters and the storage backend. Refusing to publish an empty backup."
    exit 1
fi
if [ "$TABLES_SKIPPED" -gt 0 ]; then
    log_info "$TABLES_SKIPPED table snapshot(s) were skipped (system keyspaces or include/exclude filters)."
fi

SCHEMA_DUMP_FILE="$BACKUP_TEMP_DIR/schema.cql"
log_info "Dumping cluster schema to $SCHEMA_DUMP_FILE..."
# STUB: no cqlsh in the sidecar.
log_warn "Failed to dump schema. The backup will be incomplete for schema-only restores."

MANIFEST_FILE="$BACKUP_TEMP_DIR/backup_manifest.json"
log_info "Creating backup manifest at $MANIFEST_FILE..."
CLUSTER_NAME=$(jq -r '.stub_cluster_name // "Unknown"' "$CONFIG_FILE")
if [ -z "$LISTEN_ADDRESS" ] || [ "$LISTEN_ADDRESS" == "null" ]; then
    NODE_IP="$(hostname -i | awk '{print $1}')"
else
    NODE_IP="$LISTEN_ADDRESS"
fi
NODE_DC=$(jq -r --arg h "$HOSTNAME" '.stub_nodes[$h].datacenter // "Unknown"' "$CONFIG_FILE")
NODE_RACK=$(jq -r --arg h "$HOSTNAME" '.stub_nodes[$h].rack // "Unknown"' "$CONFIG_FILE")
NODE_TOKENS=$(jq -r --arg h "$HOSTNAME" '(.stub_nodes[$h].tokens // []) | join(",")' "$CONFIG_FILE")

jq -n \
  --arg cluster_name "$CLUSTER_NAME" \
  --arg backup_id "$BACKUP_TAG" \
  --arg backup_type "full" \
  --arg timestamp "$(date --iso-8601=seconds)" \
  --arg node_ip "$NODE_IP" \
  --arg node_dc "$NODE_DC" \
  --arg node_rack "$NODE_RACK" \
  --arg tokens "$NODE_TOKENS" \
  --argjson tables_count "$TABLES_BACKED_UP_SUCCESS_COUNT" \
  '{
    "cluster_name": $cluster_name,
    "backup_id": $backup_id,
    "backup_type": $backup_type,
    "timestamp_utc": $timestamp,
    "source_node": {
      "ip_address": $node_ip,
      "datacenter": $node_dc,
      "rack": $node_rack,
      "tokens": ($tokens | split(","))
    },
    "tables_backed_up_count": $tables_count
  }' > "$MANIFEST_FILE"
log_success "Manifest created successfully."

log_info "Uploading manifest file..."
MANIFEST_KEY="$HOSTNAME/$BACKUP_TAG/backup_manifest.json"
if ! storage_upload_file "$MANIFEST_FILE" "$MANIFEST_KEY"; then
  log_error "Failed to upload manifest to $(storage_uri "$MANIFEST_KEY"). The backup is not properly indexed."
  exit 1
fi
log_success "Manifest uploaded successfully."

log_info "Uploading schema mapping file..."
if ! storage_upload_file "$SCHEMA_MAP_FILE" "$HOSTNAME/$BACKUP_TAG/schema_mapping.json"; then
    log_error "Failed to upload schema mapping file. Granular restore from this backup will not work."
else
    log_success "Schema mapping file uploaded successfully."
fi

if [ "$UPLOAD_ERRORS" -gt 0 ]; then
    log_error "--- Granular Cassandra Backup Process Finished with $UPLOAD_ERRORS ERRORS ---"
    log_error "Summary: $TABLES_BACKED_UP_SUCCESS_COUNT / $TOTAL_TABLES_ATTEMPTED tables uploaded successfully ($SNAPSHOT_DIRS_FOUND snapshot(s) found, $TABLES_SKIPPED skipped)."
    log_error "The following tables failed to back up:"
    for f in "$ERROR_DIR"/*; do
        log_error "  - $(basename "$f")"
    done
    exit 1
else
    log_success "--- Granular Cassandra Backup Process Finished Successfully ---"
    log_success "Summary: all $TABLES_BACKED_UP_SUCCESS_COUNT table(s) selected for backup were uploaded to $(storage_uri "$HOSTNAME/$BACKUP_TAG") ($SNAPSHOT_DIRS_FOUND snapshot(s) found, $TABLES_SKIPPED skipped)."
fi

exit 0
