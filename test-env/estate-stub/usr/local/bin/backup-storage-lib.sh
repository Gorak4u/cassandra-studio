#!/bin/bash
#
# Object-storage abstraction for the Cassandra backup and restore scripts.
#
# WHY THIS EXISTS
# ---------------
# full-backup-to-s3.sh, incremental-backup-to-s3.sh, restore-from-s3.sh,
# verify-backup.sh and backup-status.sh all called the `aws` CLI directly, so
# the backup subsystem only ever worked against AWS S3 even though
# `backup_backend` was already a parameter. Every other value of that parameter
# silently skipped the upload entirely and still exited 0.
#
# Every provider-specific command now lives behind a storage_* function, so the
# calling scripts contain no vendor CLI invocations at all.
#
# SUPPORTED BACKENDS
#   s3     AWS S3 via the `aws` CLI. With storage_endpoint set this also covers
#          every S3-compatible service: MinIO, Ceph RGW, Wasabi, Cloudflare R2,
#          DigitalOcean Spaces, NetApp StorageGRID, and Google Cloud Storage
#          through its S3 interoperability (XML) endpoint.
#   gcs    Google Cloud Storage, native, via `gcloud storage` (preferred) or
#          `gsutil` as a fallback.
#   azure  Azure Blob Storage via the `az storage blob` CLI.
#   local  A filesystem path -- an NFS/SMB mount, a SAN volume, or a local disk.
#          Also what the test suite uses, because it needs no cloud account.
#
# A "key" in this API is always the provider-independent object path *inside*
# the container, e.g. "cass1/2026-09-11-15-46/bkp_test/t_users.tar.gz.enc".
# Callers never build a URI themselves; storage_uri() renders one for logging.
#
# CONTRACT
#   - Every function returns 0 on success and non-zero on failure. None of them
#     call exit, so a caller with `set -e` keeps control of its own flow.
#   - stdout is reserved for data/results. Progress chatter goes to stderr.
#   - All of these are safe to call from an xargs subshell; see
#     storage_export_functions below.

# --- Configuration, populated by storage_init ---
STORAGE_BACKEND=""
STORAGE_CONTAINER=""
STORAGE_ENDPOINT=""
STORAGE_REGION=""
STORAGE_LOCAL_PATH=""
STORAGE_AZURE_ACCOUNT=""
STORAGE_GCS_CLI=""
STORAGE_THROTTLE=""

# Set by storage_init to the CLI actually used, for error messages.
STORAGE_CLI=""

# --- Internal logging -------------------------------------------------------
# The calling scripts each define log_info/log_warn/log_error. When this library
# is sourced by something that does not (or from an xargs subshell where the
# exports did not survive), fall back to plain stderr rather than dying on an
# unbound function.
_storage_log() {
    if declare -F log_warn >/dev/null 2>&1; then
        log_warn "$1"
    else
        printf '%s\n' "$1" >&2
    fi
}

# --- Initialisation ---------------------------------------------------------
# storage_init <config.json path>
# Reads every storage-related key out of the backup config and validates the
# combination. Returns non-zero on an unusable configuration.
storage_init() {
    local config_file="$1"

    if [ ! -f "$config_file" ]; then
        _storage_log "storage_init: config file not found: $config_file"
        return 1
    fi

    STORAGE_BACKEND=$(jq -r '.backup_backend // "s3"' "$config_file")
    STORAGE_CONTAINER=$(jq -r '.s3_bucket_name // ""' "$config_file")
    STORAGE_ENDPOINT=$(jq -r '.storage_endpoint // ""' "$config_file")
    STORAGE_REGION=$(jq -r '.storage_region // ""' "$config_file")
    STORAGE_LOCAL_PATH=$(jq -r '.storage_local_path // ""' "$config_file")
    STORAGE_AZURE_ACCOUNT=$(jq -r '.storage_azure_account // ""' "$config_file")

    # "null" is what jq prints for an explicit JSON null, which is not the same
    # as an absent key and would otherwise be used as a literal value.
    [ "$STORAGE_ENDPOINT" = "null" ] && STORAGE_ENDPOINT=""
    [ "$STORAGE_REGION" = "null" ] && STORAGE_REGION=""
    [ "$STORAGE_LOCAL_PATH" = "null" ] && STORAGE_LOCAL_PATH=""
    [ "$STORAGE_AZURE_ACCOUNT" = "null" ] && STORAGE_AZURE_ACCOUNT=""

    case "$STORAGE_BACKEND" in
        s3)
            STORAGE_CLI="aws"
            ;;
        gcs)
            # `gcloud storage` is the current tool; gsutil still ships on older
            # images and in the standalone SDK, so accept either.
            if command -v gcloud >/dev/null 2>&1; then
                STORAGE_GCS_CLI="gcloud"
                STORAGE_CLI="gcloud"
            elif command -v gsutil >/dev/null 2>&1; then
                STORAGE_GCS_CLI="gsutil"
                STORAGE_CLI="gsutil"
            else
                _storage_log "backup_backend is 'gcs' but neither 'gcloud' nor 'gsutil' is installed."
                return 1
            fi
            ;;
        azure)
            STORAGE_CLI="az"
            if [ -z "$STORAGE_AZURE_ACCOUNT" ]; then
                _storage_log "backup_backend is 'azure' but storage_azure_account is not set."
                return 1
            fi
            ;;
        local)
            STORAGE_CLI="cp"
            if [ -z "$STORAGE_LOCAL_PATH" ]; then
                _storage_log "backup_backend is 'local' but storage_local_path is not set."
                return 1
            fi
            ;;
        *)
            _storage_log "Unsupported backup_backend '$STORAGE_BACKEND'. Supported: s3, gcs, azure, local."
            return 1
            ;;
    esac

    if [ "$STORAGE_BACKEND" != "local" ] && [ -z "$STORAGE_CONTAINER" ]; then
        _storage_log "s3_bucket_name (the bucket/container name) is required for backend '$STORAGE_BACKEND'."
        return 1
    fi

    if [ "$STORAGE_BACKEND" != "local" ] && ! command -v "$STORAGE_CLI" >/dev/null 2>&1; then
        _storage_log "Required CLI '$STORAGE_CLI' for backend '$STORAGE_BACKEND' is not installed or not in PATH."
        return 1
    fi

    return 0
}

# storage_set_container <name>   -- honour a --s3-bucket style override
storage_set_container() { STORAGE_CONTAINER="$1"; }

# storage_set_throttle <rate>    -- e.g. "50M/s"; empty disables throttling
storage_set_throttle() { STORAGE_THROTTLE="$1"; }

# --- Helpers ----------------------------------------------------------------

# Render a human-readable URI for a key. Logging only; never parsed.
storage_uri() {
    local key="${1:-}"
    case "$STORAGE_BACKEND" in
        s3)    printf 's3://%s/%s' "$STORAGE_CONTAINER" "$key" ;;
        gcs)   printf 'gs://%s/%s' "$STORAGE_CONTAINER" "$key" ;;
        azure) printf 'azure://%s/%s/%s' "$STORAGE_AZURE_ACCOUNT" "$STORAGE_CONTAINER" "$key" ;;
        local) printf '%s/%s' "${STORAGE_LOCAL_PATH%/}" "$key" ;;
        *)     printf '%s' "$key" ;;
    esac
}

# The aws CLI gained --endpoint-url long before AWS_ENDPOINT_URL existed, so the
# flag is used rather than the environment variable for maximum compatibility
# with older awscli 1.x builds still shipped by distributions.
_storage_aws() {
    local -a args=()
    [ -n "$STORAGE_ENDPOINT" ] && args+=("--endpoint-url" "$STORAGE_ENDPOINT")
    [ -n "$STORAGE_REGION" ] && args+=("--region" "$STORAGE_REGION")
    if [ -n "$STORAGE_THROTTLE" ]; then
        AWS_MAX_BANDWIDTH="$STORAGE_THROTTLE" command aws "${args[@]}" "$@"
    else
        command aws "${args[@]}" "$@"
    fi
}

_storage_local_path_for() {
    printf '%s/%s' "${STORAGE_LOCAL_PATH%/}" "$1"
}

# --- Credential / reachability probe ---------------------------------------
storage_check_credentials() {
    case "$STORAGE_BACKEND" in
        s3)
            # A bucket-scoped call, not sts:GetCallerIdentity. GetCallerIdentity
            # needs an STS endpoint that S3-compatible services generally do not
            # implement, and it proves nothing about S3 access anyway. Listing
            # the target bucket is the permission the backup actually needs.
            _storage_aws s3api list-objects-v2 --bucket "$STORAGE_CONTAINER" --max-items 1 >/dev/null 2>&1 && return 0
            # A bucket that does not exist yet is not a credential failure; the
            # caller creates it next. Distinguish the two.
            _storage_aws s3api list-buckets >/dev/null 2>&1
            ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage ls "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1 && return 0
                command gcloud storage ls >/dev/null 2>&1
            else
                command gsutil ls "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1 && return 0
                command gsutil ls >/dev/null 2>&1
            fi
            ;;
        azure)
            command az storage container exists \
                --account-name "$STORAGE_AZURE_ACCOUNT" \
                --name "$STORAGE_CONTAINER" \
                --auth-mode login >/dev/null 2>&1
            ;;
        local)
            # Writable is the only meaningful credential for a mount.
            mkdir -p "$STORAGE_LOCAL_PATH" 2>/dev/null || return 1
            [ -w "$STORAGE_LOCAL_PATH" ]
            ;;
    esac
}

# --- Container lifecycle ----------------------------------------------------
storage_container_exists() {
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3api head-bucket --bucket "$STORAGE_CONTAINER" >/dev/null 2>&1 ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage buckets describe "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1
            else
                command gsutil ls -b "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1
            fi
            ;;
        azure)
            command az storage container exists \
                --account-name "$STORAGE_AZURE_ACCOUNT" --name "$STORAGE_CONTAINER" \
                --auth-mode login --query exists -o tsv 2>/dev/null | grep -qi '^true$'
            ;;
        local) [ -d "$(_storage_local_path_for '')" ] ;;
    esac
}

# storage_create_container [want_lock]
# want_lock=true asks for an immutability-enabled container where the provider
# supports it at creation time. Failure to get immutability is reported by
# storage_object_lock_supported, not by a non-zero return here.
storage_create_container() {
    local want_lock="${1:-false}"
    case "$STORAGE_BACKEND" in
        s3)
            local -a args=("--bucket" "$STORAGE_CONTAINER")
            local region="$STORAGE_REGION"
            [ -z "$region" ] && region=$(command aws configure get region 2>/dev/null || echo "us-east-1")
            [ -z "$region" ] && region="us-east-1"
            # us-east-1 must NOT be sent as a LocationConstraint.
            if [ "$region" != "us-east-1" ]; then
                args+=("--create-bucket-configuration" "LocationConstraint=$region")
            fi
            if [ "$want_lock" = "true" ]; then
                if _storage_aws s3api create-bucket "${args[@]}" --object-lock-enabled-for-bucket >/dev/null 2>&1; then
                    return 0
                fi
                _storage_log "Could not create bucket with Object Lock (possibly missing s3:PutBucketObjectLockConfiguration). Retrying without it."
            fi
            _storage_aws s3api create-bucket "${args[@]}" >/dev/null 2>&1
            ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                local -a args=("gs://${STORAGE_CONTAINER}")
                [ -n "$STORAGE_REGION" ] && args+=("--location=$STORAGE_REGION")
                command gcloud storage buckets create "${args[@]}" >/dev/null 2>&1
            else
                local -a args=()
                [ -n "$STORAGE_REGION" ] && args+=("-l" "$STORAGE_REGION")
                command gsutil mb "${args[@]}" "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1
            fi
            ;;
        azure)
            command az storage container create \
                --account-name "$STORAGE_AZURE_ACCOUNT" --name "$STORAGE_CONTAINER" \
                --auth-mode login >/dev/null 2>&1
            ;;
        local)
            mkdir -p "$(_storage_local_path_for '')"
            ;;
    esac
}

# storage_object_lock_supported
# True only when the container really is configured for immutable writes, so the
# caller can degrade instead of failing every upload.
storage_object_lock_supported() {
    case "$STORAGE_BACKEND" in
        s3)
            local cfg
            # `|| true` keeps a missing lock configuration (a normal bucket, or
            # no s3:GetObjectLockConfiguration permission) from tripping `set -e`
            # in the caller. The original code tested $? after a plain
            # assignment, which under `set -e` aborted before it could degrade.
            cfg=$(_storage_aws s3api get-object-lock-configuration --bucket "$STORAGE_CONTAINER" 2>/dev/null || true)
            printf '%s' "$cfg" | jq -e '.ObjectLockConfiguration.ObjectLockEnabled == "Enabled"' >/dev/null 2>&1
            ;;
        gcs)
            # GCS expresses immutability as a bucket retention policy.
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage buckets describe "gs://${STORAGE_CONTAINER}" \
                    --format='value(retentionPolicy.retentionPeriod)' 2>/dev/null | grep -qE '^[0-9]+$'
            else
                command gsutil retention get "gs://${STORAGE_CONTAINER}" 2>/dev/null | grep -qi 'Retention Period'
            fi
            ;;
        azure)
            # Azure immutability policies are per-container and require a
            # version-level or container-level policy that `az storage blob`
            # cannot set per object.
            return 1
            ;;
        local)
            return 1
            ;;
    esac
}

# storage_set_lifecycle <days>
# Best effort: a provider that rejects the policy must not fail the backup.
storage_set_lifecycle() {
    local days="$1"
    if ! [[ "$days" =~ ^[1-9][0-9]*$ ]]; then
        return 0
    fi
    case "$STORAGE_BACKEND" in
        s3)
            local policy
            policy=$(jq -n --arg ID "auto-expire-backups" --argjson DAYS "$days" \
                '{Rules:[{ID:$ID,Filter:{Prefix:""},Status:"Enabled",Expiration:{Days:$DAYS}}]}')
            _storage_aws s3api put-bucket-lifecycle-configuration \
                --bucket "$STORAGE_CONTAINER" --lifecycle-configuration "$policy" >/dev/null 2>&1
            ;;
        gcs)
            local tmp
            tmp=$(mktemp)
            jq -n --argjson DAYS "$days" \
                '{rule:[{action:{type:"Delete"},condition:{age:$DAYS}}]}' > "$tmp"
            local rc=0
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage buckets update "gs://${STORAGE_CONTAINER}" \
                    --lifecycle-file="$tmp" >/dev/null 2>&1 || rc=$?
            else
                command gsutil lifecycle set "$tmp" "gs://${STORAGE_CONTAINER}" >/dev/null 2>&1 || rc=$?
            fi
            rm -f "$tmp"
            return $rc
            ;;
        azure)
            # Azure lifecycle management is an account-level policy, not a
            # container-level one, so it is out of scope for a node-local script.
            return 0
            ;;
        local)
            return 0
            ;;
    esac
}

# --- Uploads ----------------------------------------------------------------

# storage_upload_file <local_file> <key>
storage_upload_file() {
    local src="$1" key="$2"
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3 cp --quiet "$src" "s3://${STORAGE_CONTAINER}/${key}" ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage cp "$src" "gs://${STORAGE_CONTAINER}/${key}" >/dev/null
            else
                command gsutil -q cp "$src" "gs://${STORAGE_CONTAINER}/${key}"
            fi
            ;;
        azure)
            command az storage blob upload --overwrite \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --name "$key" --file "$src" --auth-mode login >/dev/null
            ;;
        local)
            local dest
            dest=$(_storage_local_path_for "$key")
            mkdir -p "$(dirname "$dest")" && cp -f "$src" "$dest"
            ;;
    esac
}

# storage_upload_stream <key>   -- object body read from stdin
# Streaming avoids staging a second full copy of every table on local disk.
storage_upload_stream() {
    local key="$1"
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3 cp --quiet - "s3://${STORAGE_CONTAINER}/${key}" ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage cp - "gs://${STORAGE_CONTAINER}/${key}" >/dev/null
            else
                command gsutil -q cp - "gs://${STORAGE_CONTAINER}/${key}"
            fi
            ;;
        azure)
            # az storage blob upload reads stdin when --file is -.
            command az storage blob upload --overwrite \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --name "$key" --file - --auth-mode login >/dev/null
            ;;
        local)
            local dest
            dest=$(_storage_local_path_for "$key")
            mkdir -p "$(dirname "$dest")" && cat > "$dest"
            ;;
    esac
}

# storage_apply_retention <key> <mode> <retain_until_iso8601>
#
# This is applied AFTER the upload, deliberately.
#
# The previous code passed --object-lock-mode / --object-lock-retain-until-date
# to `aws s3 cp`, which does not accept them -- only `aws s3api put-object`
# does. Every upload therefore failed with
#     Unknown options: --object-lock-mode,GOVERNANCE,--object-lock-retain-until-date,...
# whenever Object Lock was enabled, which made the whole backup exit 1. Switching
# to `s3api put-object` would have fixed the flags but lost multipart upload and
# the ability to stream from stdin, so retention is instead set as a second call
# on the object that `s3 cp` just wrote. Verified equivalent: the resulting
# get-object-retention reports the same Mode and RetainUntilDate.
storage_apply_retention() {
    local key="$1" mode="$2" until_date="$3"
    case "$STORAGE_BACKEND" in
        s3)
            local retention
            retention=$(jq -n --arg m "$mode" --arg d "$until_date" \
                '{Mode:$m,RetainUntilDate:$d}')
            _storage_aws s3api put-object-retention \
                --bucket "$STORAGE_CONTAINER" --key "$key" --retention "$retention" >/dev/null 2>&1
            ;;
        gcs)
            # GCS per-object immutability is an event-based hold; the duration
            # comes from the bucket retention policy set at creation.
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage objects update "gs://${STORAGE_CONTAINER}/${key}" \
                    --event-based-hold >/dev/null 2>&1
            else
                command gsutil retention event hold "gs://${STORAGE_CONTAINER}/${key}" >/dev/null 2>&1
            fi
            ;;
        azure|local)
            return 1
            ;;
    esac
}

# --- Downloads --------------------------------------------------------------

# storage_download_file <key> <local_file>
storage_download_file() {
    local key="$1" dest="$2"
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3 cp --quiet "s3://${STORAGE_CONTAINER}/${key}" "$dest" ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage cp "gs://${STORAGE_CONTAINER}/${key}" "$dest" >/dev/null
            else
                command gsutil -q cp "gs://${STORAGE_CONTAINER}/${key}" "$dest"
            fi
            ;;
        azure)
            command az storage blob download \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --name "$key" --file "$dest" --auth-mode login >/dev/null
            ;;
        local)
            cp -f "$(_storage_local_path_for "$key")" "$dest"
            ;;
    esac
}

# storage_download_stream <key>  -- object body written to stdout
storage_download_stream() {
    local key="$1"
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3 cp "s3://${STORAGE_CONTAINER}/${key}" - 2>/dev/null ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage cat "gs://${STORAGE_CONTAINER}/${key}" 2>/dev/null
            else
                command gsutil cat "gs://${STORAGE_CONTAINER}/${key}" 2>/dev/null
            fi
            ;;
        azure)
            command az storage blob download \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --name "$key" --file /dev/stdout --auth-mode login 2>/dev/null
            ;;
        local)
            cat "$(_storage_local_path_for "$key")" 2>/dev/null
            ;;
    esac
}

# --- Listing ----------------------------------------------------------------

# storage_list_prefixes <prefix>
# One immediate "directory" name per line, no trailing slash. Used to enumerate
# hosts and backup sets.
storage_list_prefixes() {
    local prefix="$1"
    # Providers differ on whether a trailing slash is required; normalise to one.
    [ -n "$prefix" ] && prefix="${prefix%/}/"
    case "$STORAGE_BACKEND" in
        s3)
            _storage_aws s3 ls "s3://${STORAGE_CONTAINER}/${prefix}" 2>/dev/null |
                awk '$1 == "PRE" { sub(/\/$/, "", $2); print $2 }'
            ;;
        gcs)
            local out
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                out=$(command gcloud storage ls "gs://${STORAGE_CONTAINER}/${prefix}" 2>/dev/null || true)
            else
                out=$(command gsutil ls "gs://${STORAGE_CONTAINER}/${prefix}" 2>/dev/null || true)
            fi
            printf '%s\n' "$out" | sed -n 's#/$##p' | awk -F/ 'NF>0 { print $NF }'
            ;;
        azure)
            command az storage blob list \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --prefix "$prefix" --delimiter "/" --auth-mode login \
                --query "[].name" -o tsv 2>/dev/null |
                sed "s#^${prefix}##" | sed 's#/.*$##' | awk 'NF' | sort -u
            ;;
        local)
            local base
            base=$(_storage_local_path_for "$prefix")
            [ -d "$base" ] || return 0
            # `-printf` is a GNU findutils extension. Linux targets have it, but
            # relying on it meant this backend silently produced NOTHING under a
            # BSD find -- with stderr suppressed, an empty listing is
            # indistinguishable from "no backups exist", which for a restore is
            # the worst possible way to fail. sed is portable and equivalent.
            find "$base" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's#/*$##; s#.*/##' | sort
            ;;
    esac
}

# storage_list_keys <prefix>
# Every object key under the prefix, recursively, one per line, container-relative.
storage_list_keys() {
    local prefix="$1"
    case "$STORAGE_BACKEND" in
        s3)
            _storage_aws s3 ls --recursive "s3://${STORAGE_CONTAINER}/${prefix}" 2>/dev/null |
                awk '{ $1=""; $2=""; $3=""; sub(/^[ \t]+/, ""); print }'
            ;;
        gcs)
            local out
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                out=$(command gcloud storage ls --recursive "gs://${STORAGE_CONTAINER}/${prefix}**" 2>/dev/null || true)
            else
                out=$(command gsutil ls -r "gs://${STORAGE_CONTAINER}/${prefix}**" 2>/dev/null || true)
            fi
            printf '%s\n' "$out" | grep -v '/$' | sed "s#^gs://${STORAGE_CONTAINER}/##" | awk 'NF'
            ;;
        azure)
            command az storage blob list \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --prefix "$prefix" --auth-mode login --query "[].name" -o tsv 2>/dev/null
            ;;
        local)
            local base
            base=$(_storage_local_path_for "")
            [ -d "${base%/}/${prefix}" ] || return 0
            find "${base%/}/${prefix}" -type f 2>/dev/null | sed "s#^${base%/}/##"
            ;;
    esac
}

# storage_object_exists <key>
storage_object_exists() {
    local key="$1"
    case "$STORAGE_BACKEND" in
        s3)    _storage_aws s3api head-object --bucket "$STORAGE_CONTAINER" --key "$key" >/dev/null 2>&1 ;;
        gcs)
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage objects describe "gs://${STORAGE_CONTAINER}/${key}" >/dev/null 2>&1
            else
                command gsutil stat "gs://${STORAGE_CONTAINER}/${key}" >/dev/null 2>&1
            fi
            ;;
        azure)
            command az storage blob exists \
                --account-name "$STORAGE_AZURE_ACCOUNT" --container-name "$STORAGE_CONTAINER" \
                --name "$key" --auth-mode login --query exists -o tsv 2>/dev/null | grep -qi '^true$'
            ;;
        local) [ -f "$(_storage_local_path_for "$key")" ] ;;
    esac
}

# storage_upload_tree <local_dir> <key_prefix> [exclude_glob]
# Recursive upload, used by the incremental backup which stages a directory tree.
storage_upload_tree() {
    local src="$1" prefix="$2" exclude="${3:-}"
    case "$STORAGE_BACKEND" in
        s3)
            local -a args=("s3" "cp" "--recursive" "--quiet")
            [ -n "$exclude" ] && args+=("--exclude" "$exclude")
            _storage_aws "${args[@]}" "$src" "s3://${STORAGE_CONTAINER}/${prefix}"
            ;;
        gcs)
            # Neither gcloud nor gsutil has a general exclude for cp, so prune
            # the excluded files from a staging copy first.
            local stage="$src"
            if [ -n "$exclude" ]; then
                stage=$(mktemp -d)
                cp -a "$src/." "$stage/"
                find "$stage" -name "$exclude" -type f -delete
            fi
            local rc=0
            if [ "$STORAGE_GCS_CLI" = "gcloud" ]; then
                command gcloud storage cp --recursive "$stage/*" "gs://${STORAGE_CONTAINER}/${prefix}" >/dev/null 2>&1 || rc=$?
            else
                command gsutil -q cp -r "$stage/*" "gs://${STORAGE_CONTAINER}/${prefix}" || rc=$?
            fi
            [ "$stage" != "$src" ] && rm -rf "$stage"
            return $rc
            ;;
        azure)
            local -a args=("storage" "blob" "upload-batch" "--overwrite"
                           "--account-name" "$STORAGE_AZURE_ACCOUNT"
                           "--destination" "$STORAGE_CONTAINER"
                           "--destination-path" "$prefix"
                           "--source" "$src" "--auth-mode" "login")
            [ -n "$exclude" ] && args+=("--pattern" "[!${exclude}]*")
            command az "${args[@]}" >/dev/null
            ;;
        local)
            local dest
            dest=$(_storage_local_path_for "$prefix")
            mkdir -p "$dest" || return 1
            if [ -n "$exclude" ]; then
                (cd "$src" && find . -type f ! -name "$exclude" -exec cp --parents {} "$dest" \;)
            else
                cp -a "$src/." "$dest/"
            fi
            ;;
    esac
}

# Make the API usable from the xargs/bash -c subshells the backup and restore
# scripts use for parallelism. Only the state actually read by the functions is
# exported, so a subshell cannot drift from the parent's configuration.
storage_export_functions() {
    export -f _storage_log _storage_aws _storage_local_path_for \
              storage_uri storage_upload_file storage_upload_stream \
              storage_apply_retention storage_download_file storage_download_stream \
              storage_list_prefixes storage_list_keys storage_object_exists \
              storage_upload_tree
    export STORAGE_BACKEND STORAGE_CONTAINER STORAGE_ENDPOINT STORAGE_REGION \
           STORAGE_LOCAL_PATH STORAGE_AZURE_ACCOUNT STORAGE_GCS_CLI \
           STORAGE_THROTTLE STORAGE_CLI
}
