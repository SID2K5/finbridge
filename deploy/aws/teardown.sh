#!/usr/bin/env bash
# Deletes everything deploy.sh created: both stacks (services, load balancer, database, Odoo
# host, network, images, log groups) and the parameters. After this nothing is billed.
#
# The database and Odoo's data are destroyed. Set KEEP_DB_SNAPSHOT=1 to keep a final snapshot
# of the FinBridge database (a snapshot is billed for its storage until you delete it).
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

confirm "This DELETES the FinBridge deployment in account ${ACCOUNT_ID}, region ${REGION}:
  the database with all its data, the Odoo host with all its data, the images and the secrets.
  It cannot be undone."

if stack_exists "$MAIN_STACK"; then
    if [ "${KEEP_DB_SNAPSHOT:-}" = "1" ]; then
        say "Final snapshot of the database"
        DB_ID="$(stack_output "$MAIN_STACK" DatabaseIdentifier)"
        SNAPSHOT="${PROJECT}-final-$(date -u +%Y%m%d%H%M%S)"
        STATE="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text)"
        [ "$STATE" = "available" ] || fail "The database is $STATE. Run ./start.sh first, or drop KEEP_DB_SNAPSHOT."
        aws rds create-db-snapshot --db-instance-identifier "$DB_ID" --db-snapshot-identifier "$SNAPSHOT" >/dev/null
        aws rds wait db-snapshot-available --db-snapshot-identifier "$SNAPSHOT"
        echo "Snapshot $SNAPSHOT kept. Delete it later with: aws rds delete-db-snapshot --db-snapshot-identifier $SNAPSHOT"
    fi
    say "Deleting stack $MAIN_STACK (10 to 15 minutes)"
    aws cloudformation delete-stack --stack-name "$MAIN_STACK"
    aws cloudformation wait stack-delete-complete --stack-name "$MAIN_STACK"
else
    echo "Stack $MAIN_STACK does not exist"
fi

if stack_exists "$ECR_STACK"; then
    say "Deleting stack $ECR_STACK with its images"
    aws cloudformation delete-stack --stack-name "$ECR_STACK"
    aws cloudformation wait stack-delete-complete --stack-name "$ECR_STACK"
else
    echo "Stack $ECR_STACK does not exist"
fi

say "Deleting the parameters under /$PROJECT"
NAMES="$(aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/$PROJECT/" \
    --query 'Parameters[].Name' --output text)"
for name in $NAMES; do
    aws ssm delete-parameter --name "$name"
    echo "deleted $name"
done

say "Looking for anything left behind"
LEFT=0
check() {   # check <what> <output of a query that should be empty>
    if [ -n "$2" ] && [ "$2" != "None" ]; then
        echo "STILL THERE  $1: $2"
        LEFT=1
    else
        echo "gone         $1"
    fi
}
check "ECS cluster"        "$(aws ecs describe-clusters --clusters "$PROJECT" --query "clusters[?status=='ACTIVE'].clusterName" --output text)"
check "load balancer"      "$(aws elbv2 describe-load-balancers --query "LoadBalancers[?LoadBalancerName=='$PROJECT-alb'].LoadBalancerName" --output text)"
check "RDS instance"       "$(aws rds describe-db-instances --query "DBInstances[?DBInstanceIdentifier=='$PROJECT-mysql'].DBInstanceIdentifier" --output text)"
check "EC2 instance"       "$(aws ec2 describe-instances --filters "Name=tag:Name,Values=$PROJECT-odoo" "Name=instance-state-name,Values=pending,running,stopping,stopped" --query 'Reservations[].Instances[].InstanceId' --output text)"
check "ECR repositories"   "$(aws ecr describe-repositories --query "repositories[?starts_with(repositoryName, '$PROJECT-')].repositoryName" --output text)"
check "log groups"         "$(aws logs describe-log-groups --log-group-name-prefix "/$PROJECT/" --query 'logGroups[].logGroupName' --output text)"
check "parameters"         "$(aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/$PROJECT/" --query 'Parameters[].Name' --output text)"
check "RDS snapshots"      "$(aws rds describe-db-snapshots --query "DBSnapshots[?starts_with(DBSnapshotIdentifier, '$PROJECT-')].DBSnapshotIdentifier" --output text)"

echo
if [ "$LEFT" = "0" ]; then
    echo "Everything is gone. Check the Billing console tomorrow to confirm that charges have stopped."
else
    echo "Something is left (a kept snapshot is expected if you asked for one). Delete it by hand,"
    echo "following the checklist in docs/deployment-guide.md."
fi
