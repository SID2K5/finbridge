#!/usr/bin/env bash
# Shows what exists and what is running, so you can see what is being billed right now.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

say "CloudFormation stacks"
for stack in "$ECR_STACK" "$MAIN_STACK"; do
    if stack_exists "$stack"; then
        echo "$stack: $(aws cloudformation describe-stacks --stack-name "$stack" --query 'Stacks[0].StackStatus' --output text)"
    else
        echo "$stack: does not exist"
    fi
done

if stack_exists "$MAIN_STACK"; then
    say "URL"
    stack_output "$MAIN_STACK" Url

    say "Fargate services (running / desired)"
    aws ecs describe-services --cluster "$PROJECT" \
        --services "$PROJECT-frontend" "$PROJECT-backend" "$PROJECT-simulator" \
        --query 'services[].[serviceName,runningCount,desiredCount]' --output text

    say "RDS instance"
    aws rds describe-db-instances --db-instance-identifier "$(stack_output "$MAIN_STACK" DatabaseIdentifier)" \
        --query 'DBInstances[0].[DBInstanceIdentifier,DBInstanceStatus,DBInstanceClass]' --output text

    say "Odoo EC2 instance"
    aws ec2 describe-instances --instance-ids "$(stack_output "$MAIN_STACK" OdooInstanceId)" \
        --query 'Reservations[0].Instances[0].[InstanceId,State.Name,InstanceType]' --output text

    say "Alarms"
    aws cloudwatch describe-alarms --alarm-name-prefix "$PROJECT-" \
        --query 'MetricAlarms[].[AlarmName,StateValue]' --output text
fi

say "Parameters under /$PROJECT (names only)"
aws ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/$PROJECT/" \
    --query 'Parameters[].Name' --output text
