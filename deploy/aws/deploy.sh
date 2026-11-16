#!/usr/bin/env bash
# Deploys FinBridge to AWS, or updates an existing deployment. Safe to run again: every step
# checks what already exists.
#
#   BOOTSTRAP_ADMIN_EMAIL=you@example.com ./deploy.sh
#
# Optional:  ALARM_EMAIL=you@example.com   receives the two CloudWatch alarms
#            IMAGE_TAG=...                 default: the git commit, or a timestamp without git
#
# What it creates is billed by the hour until you run stop.sh or teardown.sh.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-}"
[ -n "$ADMIN_EMAIL" ] || fail "Set BOOTSTRAP_ADMIN_EMAIL to the email of the first FinBridge administrator."
command -v docker >/dev/null 2>&1 || fail "Docker is not installed."
docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker Desktop."

if git -C "$REPO_ROOT" rev-parse --short HEAD >/dev/null 2>&1; then
    DEFAULT_TAG="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
    # Uncommitted changes would make the tag lie about what is in the image.
    if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
        DEFAULT_TAG="${DEFAULT_TAG}-$(date -u +%Y%m%d%H%M%S)"
    fi
else
    DEFAULT_TAG="$(date -u +%Y%m%d%H%M%S)"
fi
TAG="${IMAGE_TAG:-$DEFAULT_TAG}"

confirm "This creates billable resources in account ${ACCOUNT_ID}, region ${REGION}:
  a load balancer, an RDS MySQL instance, three Fargate tasks, one EC2 instance and
  public IPv4 addresses. They cost money every hour until you stop or delete them.
  Have you set a billing budget with an email alert? (docs/deployment-guide.md, step 2)"

# ---------------------------------------------------------------------------------------------
say "1 of 5  Image repositories"
aws cloudformation deploy --stack-name "$ECR_STACK" --template-file "$(native_path "$HERE/ecr.yaml")" \
    --parameter-overrides "ProjectName=$PROJECT" --no-fail-on-empty-changeset

# ---------------------------------------------------------------------------------------------
say "2 of 5  Build and push the three images with tag $TAG"
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
for name in backend simulator frontend; do
    case "$name" in
        simulator) context="$REPO_ROOT/payment-simulator" ;;
        *)         context="$REPO_ROOT/$name" ;;
    esac
    image="$REGISTRY/$PROJECT-$name:$TAG"
    if aws ecr describe-images --repository-name "$PROJECT-$name" --image-ids "imageTag=$TAG" >/dev/null 2>&1; then
        echo "$image is already in ECR"
        continue
    fi
    echo "Building $image"
    # Fargate runs x86-64 here; say so, in case this machine is ARM.
    docker build --platform linux/amd64 -t "$image" "$(native_path "$context")"
    docker push "$image"
done

# ---------------------------------------------------------------------------------------------
say "3 of 5  Secrets in Parameter Store (created once, never printed, never overwritten)"
random_secret() {   # random_secret <bytes>: URL-safe, no characters that need quoting
    openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '-_'
}
put_secret() {      # put_secret <name> <value>
    if parameter_exists "/$PROJECT/$1"; then
        echo "/$PROJECT/$1 exists"
    else
        aws ssm put-parameter --name "/$PROJECT/$1" --type SecureString --value "$2" >/dev/null
        echo "/$PROJECT/$1 created"
    fi
}
command -v openssl >/dev/null 2>&1 || fail "openssl is needed to generate secrets (Git Bash includes it)."
put_secret db-password              "$(random_secret 24)"
put_secret jwt-secret               "$(random_secret 48)"
put_secret sim-api-key              "$(random_secret 24)"
put_secret odoo-pg-password         "$(random_secret 24)"
put_secret bootstrap-admin-password "$(random_secret 18)"
# A placeholder so that the backend task can start. The Odoo instance replaces it on first boot.
put_secret odoo-api-key             "not-yet-issued-by-odoo"

# ---------------------------------------------------------------------------------------------
say "4 of 5  Network, database, Odoo host, load balancer and services (15 to 20 minutes the first time)"
aws cloudformation deploy --stack-name "$MAIN_STACK" --template-file "$(native_path "$HERE/finbridge.yaml")" \
    --capabilities CAPABILITY_IAM --no-fail-on-empty-changeset \
    --parameter-overrides "ProjectName=$PROJECT" "ImageTag=$TAG" "DesiredCount=1" \
        "BootstrapAdminEmail=$ADMIN_EMAIL" "AlarmEmail=${ALARM_EMAIL:-}"

# ---------------------------------------------------------------------------------------------
say "5 of 5  Wait for Odoo to issue its API key, then restart the backend so that it reads it"
key_is_real() {
    [ "$(aws ssm get-parameter --name "/$PROJECT/odoo-api-key" --with-decryption \
        --query Parameter.Value --output text)" != "not-yet-issued-by-odoo" ]
}
if key_is_real; then
    echo "The Odoo API key is already in Parameter Store"
else
    echo "Odoo is creating its database on first boot. This takes 5 to 10 minutes."
    for attempt in $(seq 1 60); do
        if key_is_real; then
            break
        fi
        sleep 20
    done
    if key_is_real; then
        aws ecs update-service --cluster "$PROJECT" --service "$PROJECT-backend" --force-new-deployment >/dev/null
        echo "Backend restarted with the real key. Give it two minutes."
    else
        echo "WARNING: Odoo has not issued a key after 20 minutes."
        echo "Look at /var/log/finbridge-odoo-setup.log on the instance (docs/deployment-guide.md, 'Odoo host')."
        echo "FinBridge itself is up; only the Odoo health check will fail until this is fixed."
    fi
fi

URL="$(stack_output "$MAIN_STACK" Url)"
cat <<EOF

FinBridge is deployed.

  URL                 $URL        (plain HTTP: do not type real passwords into it)
  Administrator       $ADMIN_EMAIL
  Its password        aws ssm get-parameter --name /$PROJECT/bootstrap-admin-password --with-decryption --query Parameter.Value --output text
  Backend logs        aws logs tail /$PROJECT/backend --follow
  Image tag           $TAG

It is being billed from now on.  Between demos: ./stop.sh    When finished: ./teardown.sh
EOF
