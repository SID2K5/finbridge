# FinBridge deployment guide

**Status on 15 November 2026: not deployed.** The container images and the complete stack have
been run and tested locally in Docker. The AWS templates pass `cfn-lint` and the scripts pass a
syntax check, but **none of them has been run against an AWS account**. Expect to fix small things
on the first real deployment, and treat every statement about AWS below as the plan, not as a
record of something that happened.

## Which path you want

This guide is the **production-shaped** design: ECS Fargate, an Application Load Balancer and RDS.
It is what the Phase 1 design asked for, and it costs real money — **Fargate has no free tier at
all**, and a load balancer is about 16 USD a month.

If the point is to show the system working on the smallest possible bill, use
[free-tier-deployment.md](free-tier-deployment.md) instead: one EC2 instance running the same
Compose stack, roughly 2.60 USD a month if you stop it between demos. Both paths share the ECR
repositories and the `/finbridge/` parameters, so **deploy one or the other, not both**.

## What gets created

| Resource | Configuration | Billed while it exists |
| --- | --- | --- |
| Application Load Balancer | Public, HTTP on port 80. `/api/*` to the backend, everything else to the frontend | Yes, by the hour, even when stopped |
| ECS Fargate, 3 services | backend (0.5 vCPU, 1 GB), frontend (0.25 vCPU, 0.5 GB), simulator (0.25 vCPU, 1 GB); one task each | Yes, while tasks run |
| RDS MySQL 8.4 | `db.t4g.micro`, single AZ, 20 GB gp3, encrypted, private subnets, 1 day of backups | Yes; storage also while stopped |
| EC2 instance for Odoo | `t3.small`, Amazon Linux 2023, Odoo 19 and PostgreSQL 16 in Docker, 20 GB encrypted volume, no SSH | Yes; the volume also while stopped |
| Public IPv4 addresses | One per running task, one for the EC2 instance, two or more for the load balancer | Yes, by the hour |
| ECR, 3 repositories | Immutable tags, scan on push, last 5 images kept | A little, for storage |
| CloudWatch | 3 log groups (7 days), 1 metric filter, 2 alarms | A little |
| Parameter Store | 7 SecureString parameters under `/finbridge/` | No (standard tier) |
| VPC | 2 public and 2 private subnets, no NAT gateway | No |

**This has not been priced.** Put it into the [AWS Pricing Calculator](https://calculator.aws/) for
your region before you deploy, and assume nothing is covered by a free tier until you have checked
your own account.

The design decisions behind it (section 12 of the architecture document) were taken as
recommended: Odoo on one EC2 instance (6), a load balancer (7), plain HTTP because there is no
domain (8), manual sync only (9). If you want any of them differently, say so before deploying.

## How the pieces talk

```
internet --HTTP 80--> load balancer --/api/*--> backend:8080 --3306--> RDS MySQL (private)
                            |                        |---8090--> simulator (Service Connect name "simulator")
                            '--everything else-->    '---8069--> Odoo on EC2 (private address)
                                frontend:8080
```

Security groups allow exactly these arrows and nothing else. The simulator, the database and Odoo
cannot be reached from the internet. Secrets are read from Parameter Store by ECS when a task
starts; no access key exists inside any container, and the task role has no permissions at all.

## 1. Tools on your machine

| Tool | Check | Notes |
| --- | --- | --- |
| AWS CLI v2 | `aws --version` | Not installed on this machine on 15 November 2026 |
| Docker Desktop | `docker info` | Must be running; the images are built locally |
| Git Bash | comes with Git for Windows | The scripts are bash. Run them from Git Bash, not PowerShell |
| Session Manager plugin | `session-manager-plugin` | Only needed to open Odoo's web page (step 6) |

Create an **IAM user or role for daily work** and configure the CLI with it (`aws configure`, or
`aws sso login`). The scripts refuse to run as the account's root user. Turn on MFA for the root
user and do not create access keys for it.

The identity you deploy with needs to create VPC, EC2, ELB, ECS, ECR, RDS, IAM roles, CloudWatch,
SNS, Service Discovery and SSM parameters. `AdministratorAccess` on a personal demo account is the
simple answer; scope it down for anything shared.

## 2. A budget, before anything else

```bash
aws budgets create-budget --account-id "$(aws sts get-caller-identity --query Account --output text)" \
  --budget '{"BudgetName":"finbridge","BudgetLimit":{"Amount":"20","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' \
  --notifications-with-subscribers '[{"Notification":{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"PERCENTAGE"},"Subscribers":[{"SubscriptionType":"EMAIL","Address":"you@example.com"}]}]'
```

Choose your own amount and address. This emails you when actual cost passes half the amount. A
budget warns; it does not stop anything.

## 3. Test locally first

```bash
cd backend && mvn clean verify && cd ..
docker compose --profile app up -d --build
```

Open http://localhost:8088. This is the same three images AWS will run. If it does not work here,
it will not work there.

## 4. Deploy

In Git Bash, from the `finbridge` folder:

```bash
cd deploy/aws
BOOTSTRAP_ADMIN_EMAIL=you@example.com ALARM_EMAIL=you@example.com ./deploy.sh
```

It asks for a typed "yes" before creating anything. Then, in order:

1. Creates the three ECR repositories (stack `finbridge-ecr`).
2. Builds the three images for `linux/amd64` and pushes them, tagged with the git commit (or a
   timestamp if the folder is not a git repository or has uncommitted changes).
3. Creates the secrets in Parameter Store with random values. Existing ones are never overwritten
   and no value is printed.
4. Creates everything else (stack `finbridge`). The first time this takes 15 to 20 minutes, most of
   it RDS.
5. Waits for the Odoo instance to create its database and API key (5 to 10 minutes), then restarts
   the backend so that it reads the real key.

At the end it prints the URL and the command that shows the administrator's password. Running
`deploy.sh` again later deploys a new image tag over the existing stack.

If you gave `ALARM_EMAIL`, AWS sends a confirmation email; the alarms notify nobody until you
click the link in it.

## 5. Check that it works (this is the Phase 8 exit evidence)

1. Open the URL. Sign in with the administrator email and the password from Parameter Store.
   **The URL is plain HTTP: use a password you use nowhere else.**
2. Monitoring > Run health check: Odoo, SOAP and REST should all answer.
3. Create a vendor and a posted bill in Odoo (step 6), then Invoices > Sync from Odoo.
4. Pay one invoice. It should succeed.
5. CloudWatch log excerpt for the evidence:
   ```bash
   aws logs tail /finbridge/backend --since 15m --format short | head -40
   ```
   The lines are JSON. Search one request across all its lines by its correlation id:
   ```bash
   aws logs filter-log-events --log-group-name /finbridge/backend --filter-pattern '"<correlation id>"'
   ```
6. `./status.sh` shows what is running.

## 6. Odoo host

Odoo's web page is not public. To open it, forward its port through Session Manager:

```bash
ODOO_ID="$(aws cloudformation describe-stacks --stack-name finbridge --query "Stacks[0].Outputs[?OutputKey=='OdooInstanceId'].OutputValue" --output text)"
aws ssm start-session --target "$ODOO_ID" --document-name AWS-StartPortForwardingSession \
  --parameters portNumber=8069,localPortNumber=8070
```

Then open http://localhost:8070 and sign in as `admin` with:

```bash
aws ssm get-parameter --name /finbridge/odoo-admin-password --with-decryption --query Parameter.Value --output text
```

A shell on the instance, for example to read the first-boot log:

```bash
aws ssm start-session --target "$ODOO_ID"
sudo cat /var/log/finbridge-odoo-setup.log
```

The integration user's API key lasts 85 days. To renew it, create a new key for
`finbridge-integration` in Odoo, store it with
`aws ssm put-parameter --name /finbridge/odoo-api-key --type SecureString --overwrite --value <key>`,
and restart the backend with
`aws ecs update-service --cluster finbridge --service finbridge-backend --force-new-deployment`.

## 7. Between demos: stop

```bash
./stop.sh      # tasks to zero, RDS stopped, EC2 stopped
./start.sh     # the reverse, in dependency order
```

While stopped you still pay for the load balancer, for RDS and EC2 storage, and for the ECR
images. AWS restarts a stopped RDS instance by itself after seven days, so run `./status.sh` if
the deployment sits idle for longer. After a start the simulator has forgotten every payment.

## 8. When finished: delete everything

```bash
./teardown.sh                      # deletes the database and Odoo's data
KEEP_DB_SNAPSHOT=1 ./teardown.sh   # keeps a final snapshot of the FinBridge database
```

It asks for a typed "yes", deletes both stacks and the parameters, and then lists anything it
still finds. Afterwards, check these console pages yourself and confirm each one is empty for the
region you used:

- [ ] CloudFormation > Stacks: no `finbridge` or `finbridge-ecr`
- [ ] EC2 > Instances, Volumes, Elastic IPs, Load Balancers, Target Groups
- [ ] ECS > Clusters
- [ ] RDS > Databases, and Snapshots (a kept final snapshot is billed until you delete it)
- [ ] ECR > Repositories
- [ ] VPC > Your VPCs: no `finbridge` VPC
- [ ] CloudWatch > Log groups: nothing under `/finbridge/`, and Alarms
- [ ] Systems Manager > Parameter Store: nothing under `/finbridge/`
- [ ] SNS > Topics: no `finbridge-alarms`
- [ ] Billing > Bills, the next day: no new charges for these services

The budget from step 2 is not deleted by the script; keep it.

## What was verified, and how

| Claim | Verified? | How |
| --- | --- | --- |
| The three images build | Yes, locally | `docker compose --profile app build` on 14 November 2026 |
| Containers run as a non-root user | Yes, locally | `docker exec ... id`: uid 100, 101, 100 |
| The whole stack works in containers | Yes, locally | 15 checks through the nginx container: headers, deep links, login, health check to Odoo, SOAP and REST by service name, a sync, a payment |
| The dashboard works under the strict CSP header | Yes, locally | Signed in and opened two pages in a browser; no console errors |
| The `prod` profile on a fresh database | Yes, locally | Throwaway MySQL 8.4: 3 migrations, no demo rows, TLS required and working, API docs closed, first admin created, JSON logs with `log.level` |
| The CloudFormation templates are valid | Partly | `cfn-lint` 1.60.1 reports nothing. They have never been deployed |
| The scripts work | No | `bash -n` syntax check only |
| The Odoo first-boot setup | Partly | The same Odoo commands were run by hand locally in Phase 3. The EC2 user data itself has never run |
| Service Connect, target groups, alarms, IAM policies | No | Written from the documentation |

## Known limits of this deployment

- **Plain HTTP.** Without a domain there is no certificate. Passwords and tokens cross the internet
  unencrypted. Use it for a demo with throwaway accounts only. With a domain: add an ACM
  certificate, an HTTPS listener and a redirect from port 80, then add HSTS.
- **One task per service, one availability zone for data.** A task restart is a short outage. The
  backend must stay at one task: its "one sync at a time" rule is per instance.
- **Tasks have public IP addresses** to pull images without a NAT gateway. No inbound rule lets the
  internet reach them, but a NAT gateway or VPC endpoints would be the stricter design.
- **No pipeline.** Images are built on your machine. There is no CI, no image signing, and the
  ECR scan results are not checked by the scripts.
- **The simulator is still a simulator**, with in-memory state.
- **Deleting the stack deletes the data** unless you ask for the snapshot. Odoo's data has no
  backup at all.
- **A wrong-profile guard:** the backend with the `prod` profile refuses to start against a database
  that holds the `dev` demo data, because Flyway finds a migration it does not know. That is
  intended; do not point `prod` at your local database.
