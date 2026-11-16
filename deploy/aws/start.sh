#!/usr/bin/env bash
# Starts what stop.sh stopped, in dependency order: database and Odoo first, then the services.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
stack_exists "$MAIN_STACK" || fail "Stack $MAIN_STACK does not exist. Run deploy.sh first."

say "RDS instance"
DB_ID="$(stack_output "$MAIN_STACK" DatabaseIdentifier)"
DB_STATE="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text)"
if [ "$DB_STATE" = "stopped" ]; then
    aws rds start-db-instance --db-instance-identifier "$DB_ID" >/dev/null
fi
echo "Waiting for $DB_ID to be available (several minutes)"
aws rds wait db-instance-available --db-instance-identifier "$DB_ID"

say "Odoo EC2 instance"
ODOO_ID="$(stack_output "$MAIN_STACK" OdooInstanceId)"
aws ec2 start-instances --instance-ids "$ODOO_ID" >/dev/null
aws ec2 wait instance-running --instance-ids "$ODOO_ID"
echo "$ODOO_ID is running; Odoo's containers start by themselves"

say "Fargate services to one task each"
for name in simulator backend frontend; do
    aws ecs update-service --cluster "$PROJECT" --service "$PROJECT-$name" --desired-count 1 >/dev/null
    echo "$PROJECT-$name: desired count 1"
done
echo "Waiting for the services to be stable"
aws ecs wait services-stable --cluster "$PROJECT" \
    --services "$PROJECT-simulator" "$PROJECT-backend" "$PROJECT-frontend"

echo
echo "Running: $(stack_output "$MAIN_STACK" Url)"
echo "The simulator keeps its payments in memory, so it has forgotten every earlier payment."
