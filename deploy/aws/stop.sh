#!/usr/bin/env bash
# Stops what bills by the hour and can be stopped, without deleting anything:
# the three Fargate tasks, the RDS instance and the Odoo EC2 instance.
#
# What KEEPS billing while stopped: the load balancer, the storage of RDS and of the EC2
# volume, and the ECR images. To stop every charge, use teardown.sh.
#
# AWS starts a stopped RDS instance again by itself after seven days. Run status.sh then.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
stack_exists "$MAIN_STACK" || fail "Stack $MAIN_STACK does not exist; nothing to stop."

say "Fargate services to zero tasks"
for name in frontend backend simulator; do
    aws ecs update-service --cluster "$PROJECT" --service "$PROJECT-$name" --desired-count 0 >/dev/null
    echo "$PROJECT-$name: desired count 0"
done

say "RDS instance"
DB_ID="$(stack_output "$MAIN_STACK" DatabaseIdentifier)"
DB_STATE="$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].DBInstanceStatus' --output text)"
if [ "$DB_STATE" = "available" ]; then
    aws rds stop-db-instance --db-instance-identifier "$DB_ID" >/dev/null
    echo "$DB_ID: stopping"
else
    echo "$DB_ID is $DB_STATE; left as it is"
fi

say "Odoo EC2 instance"
ODOO_ID="$(stack_output "$MAIN_STACK" OdooInstanceId)"
aws ec2 stop-instances --instance-ids "$ODOO_ID" >/dev/null
echo "$ODOO_ID: stopping"

echo
echo "Stopped. Still billed: the load balancer and storage. Start again with ./start.sh"
