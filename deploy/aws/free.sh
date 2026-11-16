#!/usr/bin/env bash
# FinBridge on ONE EC2 instance, for an AWS account on the Free plan.
#
#   ./free.sh deploy           build and push the images, then create or update the stack
#   ./free.sh start            start the instance (the public address changes)
#   ./free.sh stop             stop the instance; only the disk is billed afterwards
#   ./free.sh status           where it is, what address, which containers are up
#   ./free.sh logs [service]   the bootstrap log, or one service's container log
#   ./free.sh odoo-key KEY     store the API key you created in the cloud Odoo, restart backend
#   ./free.sh credentials      print every sign-in for the deployed system (secrets)
#   ./free.sh admin-password   print the dashboard administrator's password (a secret)
#   ./free.sh forward PORT     the command that brings a closed port to your own machine
#   ./free.sh refresh-env      rebuild the instance's .env parameter from the secrets
#   ./free.sh teardown         delete everything this created
#
# Settings: the same as the other scripts (FINBRIDGE_PROJECT, AWS_REGION, AWS_PROFILE),
# plus FINBRIDGE_ADMIN_EMAIL for the first administrator and FINBRIDGE_INSTANCE_TYPE.
#
# This stack is separate from the Fargate one in finbridge.yaml. Do not run both: they
# would share the ECR repositories and the Parameter Store names, which is fine, but you
# would pay for two copies of everything.
usage() { sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
case "${1:-}" in
    ""|-h|--help|help) usage; exit 0 ;;
    deploy|start|stop|status|logs|odoo-key|admin-password|odoo-admin-password|forward|teardown|refresh-env|credentials) ;;
    *) printf 'Unknown command: %s

' "$1" >&2; usage >&2; exit 1 ;;
esac

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

FREE_STACK="${PROJECT}-free"
INSTANCE_TYPE="${FINBRIDGE_INSTANCE_TYPE:-t3.small}"
ADMIN_EMAIL="${FINBRIDGE_ADMIN_EMAIL:-admin@finbridge.local}"
TEMPLATE="$(native_path "$HERE/free-tier.yaml")"

instance_id() {
    stack_exists "$FREE_STACK" || fail "Stack $FREE_STACK does not exist. Run './free.sh deploy' first."
    stack_output "$FREE_STACK" InstanceId
}

public_ip() {
    aws ec2 describe-instances --instance-ids "$1" \
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
}

instance_state() {
    aws ec2 describe-instances --instance-ids "$1" \
        --query 'Reservations[0].Instances[0].State.Name' --output text
}

secret_value() {   # secret_value <parameter name under /PROJECT/>
    aws ssm get-parameter --name "/$PROJECT/$1" --with-decryption \
        --query Parameter.Value --output text
}

# Runs a shell command on the instance through Session Manager and prints its output.
# No open port and no SSH key are involved.
on_instance() {    # on_instance <instance id> <command>
    local id="$1" cmd="$2" cid
    cid="$(aws ssm send-command --instance-ids "$id" \
            --document-name AWS-RunShellScript \
            --parameters "commands=[\"{ $cmd ; } 2>&1 | $ASCII_ONLY\"]" \
            --query Command.CommandId --output text 2>/dev/null)" \
        || fail "Could not reach the instance with Session Manager. Is it running, and has it finished booting?"
    aws ssm wait command-executed --command-id "$cid" --instance-id "$id" 2>/dev/null || true
    aws ssm get-command-invocation --command-id "$cid" --instance-id "$id" \
        --query 'join(``, [StandardOutputContent, StandardErrorContent])' --output text
}


# The three application images are the only thing in the Compose file that depends on
# the account, so they are placeholders in compose.cloud.yml until this point.
# Comments are stripped because a Standard tier parameter holds 4096 characters and the
# commented file is longer than that. The instance does not need them; the repository's
# copy keeps them. An Advanced tier parameter would cost 0.05 USD a month instead.
render_compose() {   # render_compose <image tag>
    sed -e "s|__REGISTRY__|$REGISTRY|g" -e "s|__PROJECT__|$PROJECT|g" -e "s|__TAG__|$1|g" "$HERE/compose.cloud.yml" \
        | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$'
}
# ------------------------------------------------------------------------------------ deploy
cmd_deploy() {
    [ -f "$REPO_ROOT/.env" ] || fail "No .env in $REPO_ROOT. The images are built from this repository, so set it up locally first."
    command -v openssl >/dev/null 2>&1 || fail "openssl is needed to generate secrets (Git Bash includes it)."
    command -v docker  >/dev/null 2>&1 || fail "Docker is needed to build the images."
    docker info >/dev/null 2>&1 || fail "Docker is not running."

    local tag
    tag="$(date -u +%Y%m%d-%H%M%S)"

    confirm "This creates billable resources in account $ACCOUNT_ID, region $REGION:
  - one $INSTANCE_TYPE EC2 instance (about 0.02 USD an hour while it RUNS)
  - one 20 GB encrypted disk (about 1.80 USD a month, also while the instance is STOPPED)
  - three ECR repositories holding about 350 MB
  - one public IPv4 address (about 0.005 USD an hour while it runs)
Nothing here is covered by the old 12-month free tier, because Fargate, load balancers and
RDS are not used at all. On a Free plan account your signup credits pay for it: roughly
1 USD a month if you stop the instance between demos. 'free.sh teardown' removes all of it."

    say "1 of 5  Image repositories"
    aws cloudformation deploy --stack-name "$ECR_STACK" \
        --template-file "$(native_path "$HERE/ecr.yaml")" \
        --parameter-overrides "ProjectName=$PROJECT" --no-fail-on-empty-changeset

    say "2 of 5  Build and push the three images with tag $tag"
    aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
    local name context image
    for name in backend simulator frontend; do
        case "$name" in
            simulator) context="$REPO_ROOT/payment-simulator" ;;
            *)         context="$REPO_ROOT/$name" ;;
        esac
        image="$REGISTRY/$PROJECT-$name:$tag"
        echo "Building $image"
        # The instance is x86-64; say so in case this machine is ARM.
        docker build --platform linux/amd64 -t "$image" "$(native_path "$context")"
        docker push "$image"
    done

    say "3 of 5  Secrets in Parameter Store"
    # Created once and never overwritten: MySQL's volume keeps the password it was created
    # with, so rotating these on a later deploy would lock the backend out of its own data.
    random_secret() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '-_'; }
    put_secret() {
        if parameter_exists "/$PROJECT/$1"; then
            echo "/$PROJECT/$1 exists, kept"
        else
            aws ssm put-parameter --name "/$PROJECT/$1" --type SecureString --value "$2" >/dev/null
            echo "/$PROJECT/$1 created"
        fi
    }
    put_secret db-password              "$(random_secret 24)"
    put_secret db-root-password         "$(random_secret 24)"
    put_secret odoo-pg-password         "$(random_secret 24)"
    put_secret odoo-admin-password      "$(random_secret 18)"
    put_secret jwt-secret               "$(random_secret 48)"
    put_secret sim-api-key              "$(random_secret 24)"
    put_secret bootstrap-admin-password "$(random_secret 18)"
    # A placeholder until you create the key inside the cloud Odoo: the backend starts
    # without it and reports Odoo as NOT_CONFIGURED.
    put_secret odoo-api-key             "not-yet-issued-by-odoo"

    say "4 of 5  The instance's configuration, as two parameters"
    write_env_parameter
    local compose_rendered
    compose_rendered="$(mktemp)"
    render_compose "$tag" > "$compose_rendered"
    local compose_size
    compose_size="$(wc -c < "$compose_rendered")"
    [ "$compose_size" -le 4096 ] || fail "The rendered Compose file is $compose_size characters; a Standard tier parameter holds 4096. Shorten compose.cloud.yml, or add --tier Advanced below (0.05 USD a month)."
    aws ssm put-parameter --name "/$PROJECT/compose" --type String --overwrite \
        --value "file://$(native_path "$compose_rendered")" >/dev/null
    rm -f "$compose_rendered"
    echo "/$PROJECT/compose written, images tagged $tag"

    say "5 of 5  Network, firewall, identity and the instance"
    # CloudFormation replaces an EC2 instance when its user data changes, and a replacement
    # starts from an empty disk: the MySQL data and the whole Odoo database, its integration
    # user and API key included, would be gone. Check before letting that happen.
    if stack_exists "$FREE_STACK"; then
        echo "Checking whether this would replace the running instance"
        local change_set="finbridge-check-$(date -u +%Y%m%d%H%M%S)" replacement
        aws cloudformation create-change-set --stack-name "$FREE_STACK" --change-set-name "$change_set"             --template-body "file://$TEMPLATE" --capabilities CAPABILITY_IAM             --parameters "ParameterKey=ProjectName,ParameterValue=$PROJECT"                          "ParameterKey=InstanceType,ParameterValue=$INSTANCE_TYPE" >/dev/null
        aws cloudformation wait change-set-create-complete --stack-name "$FREE_STACK"             --change-set-name "$change_set" 2>/dev/null || true
        replacement="$(aws cloudformation describe-change-set --stack-name "$FREE_STACK"             --change-set-name "$change_set"             --query "Changes[?ResourceChange.LogicalResourceId=='Instance'].ResourceChange.Replacement"             --output text 2>/dev/null || true)"
        aws cloudformation delete-change-set --stack-name "$FREE_STACK" --change-set-name "$change_set" >/dev/null 2>&1 || true
        case "$replacement" in
            True|Conditional)
                confirm "WARNING: this deployment would REPLACE the running instance.
A replacement starts from an empty disk. You would lose the FinBridge database and the whole
Odoo database, including its integration user and API key, and you would set Odoo up again.
Take a snapshot first if you need the data." ;;
        esac
    fi
    aws cloudformation deploy --stack-name "$FREE_STACK" --template-file "$TEMPLATE" \
        --capabilities CAPABILITY_IAM \
        --parameter-overrides "ProjectName=$PROJECT" "InstanceType=$INSTANCE_TYPE" \
        --no-fail-on-empty-changeset

    local id ip
    id="$(instance_id)"
    ip="$(public_ip "$id")"
    cat <<SUMMARY

Deployed. The instance is $id at $ip.

The first boot installs Docker, pulls six images and creates the Odoo database. That takes
about 10 minutes; until then the dashboard answers with an error. Watch it with:
    ./free.sh logs

Then, in this order:
  1. ./free.sh admin-password          the dashboard password for $ADMIN_EMAIL
  2. open http://$ip                   the dashboard
  3. ./free.sh forward 8069            then open http://localhost:8069 for Odoo
       sign in as admin with the password from: ./free.sh odoo-admin-password
       create vendors and post bills, then Settings > Users > create the integration user
       and its API key
  4. ./free.sh odoo-key <the key>      stores it and restarts the backend
  5. ./free.sh stop                    when the demo is over
SUMMARY
}

# Builds the whole env file and stores it as one SecureString. The instance writes it to
# /opt/finbridge/.env at boot. One parameter instead of fifteen, and the same file the
# local stack uses, so the cloud runs the configuration that was tested.
write_env_parameter() {
    local env_file
    env_file="$(mktemp)"
    cat > "$env_file" <<ENVFILE
# Written by deploy/aws/free.sh. Do not edit on the instance: the next deploy overwrites it.
DB_NAME=finbridge
DB_USERNAME=finbridge
DB_PASSWORD=$(secret_value db-password)
DB_ROOT_PASSWORD=$(secret_value db-root-password)
APP_PROFILE=prod
JWT_SECRET=$(secret_value jwt-secret)
JWT_EXPIRATION_MINUTES=60
BOOTSTRAP_ADMIN_EMAIL=$ADMIN_EMAIL
BOOTSTRAP_ADMIN_PASSWORD=$(secret_value bootstrap-admin-password)
BOOTSTRAP_ADMIN_NAME=System Administrator
ODOO_DB=finbridge
ODOO_PG_PASSWORD=$(secret_value odoo-pg-password)
ODOO_ADMIN_PASSWORD=$(secret_value odoo-admin-password)
ODOO_API_KEY=$(secret_value odoo-api-key)
SIM_API_KEY=$(secret_value sim-api-key)
ENVFILE
    aws ssm put-parameter --name "/$PROJECT/env" --type SecureString --overwrite \
        --value "file://$(native_path "$env_file")" >/dev/null
    rm -f "$env_file"
    echo "/$PROJECT/env written"
}

# ------------------------------------------------------------------------------------ run
cmd_start() {
    local id ip
    id="$(instance_id)"
    aws ec2 start-instances --instance-ids "$id" >/dev/null
    echo "Waiting for $id"
    aws ec2 wait instance-running --instance-ids "$id"
    ip="$(public_ip "$id")"
    echo
    echo "Running at http://$ip"
    echo "The containers need a minute or two. The address is new: an Elastic IP would be"
    echo "billed while idle, so this stack does not keep one."
    echo "The simulator keeps its payments in memory and has forgotten every earlier one."
}

cmd_stop() {
    local id
    id="$(instance_id)"
    aws ec2 stop-instances --instance-ids "$id" >/dev/null
    echo "Stopping $id. MySQL and Odoo keep their data on the disk."
    echo "While it is stopped you are billed for the disk only, about 1.80 USD a month."
}

cmd_status() {
    local id state ip
    id="$(instance_id)"
    state="$(instance_state "$id")"
    ip="$(public_ip "$id")"
    printf 'stack     %s\n' "$(aws cloudformation describe-stacks --stack-name "$FREE_STACK" --query 'Stacks[0].StackStatus' --output text)"
    printf 'instance  %s (%s), %s\n' "$id" "$INSTANCE_TYPE" "$state"
    printf 'address   %s\n' "$ip"
    if [ "$state" = "running" ]; then
        printf 'dashboard http://%s\n\n' "$ip"
        on_instance "$id" "cd /opt/finbridge && docker compose ps --format '{{.Name}}  {{.State}}  {{.Status}}' ; free -m | head -2"
    else
        printf '\nNot running. Start it with: ./free.sh start\n'
    fi
}

cmd_logs() {
    local service="${1:-}" id
    id="$(instance_id)"
    if [ -n "$service" ]; then
        on_instance "$id" "cd /opt/finbridge && docker compose logs --tail 80 --no-color $service"
    else
        on_instance "$id" "tail -n 60 /var/log/finbridge-bootstrap.log"
    fi
}

cmd_odoo_key() {
    local key="${1:-}"
    [ -n "$key" ] || fail "Usage: ./free.sh odoo-key <the API key from Odoo>"
    [ "$key" != "not-yet-issued-by-odoo" ] || fail "That is the placeholder, not a key."
    local id
    id="$(instance_id)"
    aws ssm put-parameter --name "/$PROJECT/odoo-api-key" --type SecureString --overwrite --value "$key" >/dev/null
    echo "Stored. Rewriting the instance's configuration."
    write_env_parameter
    on_instance "$id" "cd /opt/finbridge && aws ssm get-parameter --region $REGION --name /$PROJECT/env --with-decryption --query Parameter.Value --output text > .env && chmod 600 .env && docker compose --env-file .env up -d --force-recreate backend && sleep 20 && docker compose ps --format '{{.Name}}  {{.Status}}'"
    echo
    echo "Check it in the dashboard: Integrations > Run health check. Odoo should answer."
}

# Everything a person needs to sign in to the deployed system. Secrets, so this is the one
# command whose output should not be pasted anywhere.
cmd_credentials() {
    local ip
    ip="$(public_ip "$(instance_id)")"
    printf '
Dashboard  http://%s
' "$ip"
    printf '  %-32s %s
' "$ADMIN_EMAIL" "$(secret_value bootstrap-admin-password)"
    local row email param
    for row in "demo.finance@finbridge.local:demo-finance-password"                "demo.operator@finbridge.local:demo-operator-password"; do
        email="${row%%:*}"; param="${row##*:}"
        if parameter_exists "/$PROJECT/$param"; then
            printf '  %-32s %s
' "$email" "$(secret_value "$param")"
        fi
    done
    printf '
Odoo       ./free.sh forward 8069, then http://localhost:8069
'
    printf '  %-32s %s

' "admin" "$(secret_value odoo-admin-password)"
}

cmd_admin_password() {
    echo "Dashboard: $ADMIN_EMAIL"
    secret_value bootstrap-admin-password
}

cmd_odoo_admin_password() {
    echo "Odoo: admin"
    secret_value odoo-admin-password
}

cmd_forward() {
    local port="${1:-}" id
    [ -n "$port" ] || fail "Usage: ./free.sh forward 8069   (or 8081 for the API, 8090 for the simulator)"
    id="$(instance_id)"
    cat <<FORWARD
Run this in another terminal, then open http://localhost:$port

    aws ssm start-session --target $id --region $REGION --document-name AWS-StartPortForwardingSession --parameters portNumber=$port,localPortNumber=$port

The shorthand form is deliberate: PowerShell strips the quotes out of the JSON form and the
CLI then rejects it. Leave the session running while you use the port; Ctrl-C closes it.

It needs the Session Manager plugin for the AWS CLI:
https://s3.amazonaws.com/session-manager-downloads/plugin/latest/windows/SessionManagerPluginSetup.exe
This is how you reach Odoo, the API and the simulator without opening a port to the internet.
FORWARD
}

cmd_teardown() {
    confirm "This DELETES the instance, its disk and every image, in account $ACCOUNT_ID.
The MySQL data and the whole Odoo database, including its integration user and API key, go
with them. This cannot be undone."
    if stack_exists "$FREE_STACK"; then
        say "Deleting stack $FREE_STACK"
        aws cloudformation delete-stack --stack-name "$FREE_STACK"
        aws cloudformation wait stack-delete-complete --stack-name "$FREE_STACK"
        echo "Deleted"
    fi
    if stack_exists "$ECR_STACK"; then
        say "Deleting stack $ECR_STACK with its images"
        aws cloudformation delete-stack --stack-name "$ECR_STACK"
        aws cloudformation wait stack-delete-complete --stack-name "$ECR_STACK"
        echo "Deleted"
    fi
    say "Parameters under /$PROJECT/"
    local names
    names="$(aws ssm get-parameters-by-path --path "/$PROJECT" --recursive \
             --query 'Parameters[].Name' --output text 2>/dev/null || true)"
    if [ -n "$names" ]; then
        # shellcheck disable=SC2086
        aws ssm delete-parameters --names $names >/dev/null
        echo "Deleted: $names"
    else
        echo "None"
    fi
    echo
    echo "Gone. Check the console's Billing page tomorrow to be sure nothing is left running."
}

case "${1:-}" in
    deploy)              cmd_deploy ;;
    start)               cmd_start ;;
    stop)                cmd_stop ;;
    status)              cmd_status ;;
    logs)                cmd_logs "${2:-}" ;;
    odoo-key)            cmd_odoo_key "${2:-}" ;;
    credentials)         cmd_credentials ;;
    admin-password)      cmd_admin_password ;;
    odoo-admin-password) cmd_odoo_admin_password ;;
    forward)             cmd_forward "${2:-}" ;;
    refresh-env)         write_env_parameter ;;
    teardown)            cmd_teardown ;;
    *)
        printf 'Unknown command: %s

' "$1" >&2
        usage >&2
        exit 1 ;;
esac
