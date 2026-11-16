#!/usr/bin/env bash
# Shared by the scripts in this folder. Not run on its own.
#
# Settings (environment variables, all optional):
#   FINBRIDGE_PROJECT   name used for every resource      default: finbridge
#   AWS_REGION          the region to deploy to           default: your AWS CLI's default region
#   AWS_PROFILE         the AWS CLI profile to use        default: the CLI's default profile
set -euo pipefail

PROJECT="${FINBRIDGE_PROJECT:-finbridge}"
ECR_STACK="${PROJECT}-ecr"
MAIN_STACK="${PROJECT}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

# Git Bash on Windows rewrites arguments that look like Unix paths ("/finbridge/db-password"
# would become "C:/Program Files/Git/finbridge/db-password"). Switch that off, and convert
# the few real file paths ourselves.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"
native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

say()  { printf '\n== %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v aws >/dev/null 2>&1 || fail "The AWS CLI v2 is not installed. See docs/deployment-guide.md, step 1."

REGION="${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
[ -n "$REGION" ] || fail "No region. Set AWS_REGION or run 'aws configure'."
export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION"
export AWS_PAGER=""
# On Windows the CLI writes in the console's codepage and dies on any non-ASCII character
# that comes back from the instance (Docker's progress output uses arrows). The CLI is a
# frozen binary and ignores PYTHONIOENCODING, so on_instance strips them at the source.
ASCII_ONLY="LC_ALL=C tr -cd '[:print:][:space:]'"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" \
    || fail "The AWS CLI has no valid credentials. Run 'aws configure' (or 'aws sso login')."
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

# Refuse to work as the root user of the account: daily work belongs to an IAM user or role.
CALLER_ARN="$(aws sts get-caller-identity --query Arn --output text)"
case "$CALLER_ARN" in
    *:root) fail "You are signed in as the account's root user. Use an IAM user or role instead." ;;
esac

stack_exists() {
    aws cloudformation describe-stacks --stack-name "$1" >/dev/null 2>&1
}

stack_output() {   # stack_output <stack> <output key>
    aws cloudformation describe-stacks --stack-name "$1" \
        --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

parameter_exists() {
    aws ssm get-parameter --name "$1" >/dev/null 2>&1
}

# Asks before anything that costs money or destroys something. FINBRIDGE_YES=1 skips the question.
confirm() {
    if [ "${FINBRIDGE_YES:-}" = "1" ]; then
        return 0
    fi
    printf '%s\nType "yes" to continue: ' "$1"
    read -r answer
    # Any capitalisation, and stray spaces: this prompt gets typed under pressure.
    answer="$(printf '%s' "$answer" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    [ "$answer" = "yes" ] || fail "Stopped. Nothing was changed by this step."
}

echo "Account ${ACCOUNT_ID}, region ${REGION}, project \"${PROJECT}\", signed in as ${CALLER_ARN}"
