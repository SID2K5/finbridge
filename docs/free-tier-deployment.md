# Deploying on a free AWS account

**Status on 16 November 2026: deployed and working.** Account <your account id>, region
`ap-southeast-2`, instance `<the instance id>` (`t3.small`). The dashboard answered on the public
address with its security headers, an administrator logged in through it, and the health check
reported SOAP and REST healthy. All three integrations then reported healthy — `odoo ok`, `soap ok`, `rest ok` — and a
synchronization run completed against the cloud Odoo. The first run found four real defects that
linting could not; they are fixed and listed at the end of this document.

This is the cheap path. The other one, [deployment-guide.md](deployment-guide.md), is the
production-shaped design: ECS Fargate, an Application Load Balancer and RDS. **That one can never
be free** — Fargate has no free tier at all — so it is not what you want here.

## What this path is

One EC2 instance running exactly the `docker compose` stack that was tested locally: MySQL, Odoo
with its PostgreSQL, the backend, the payment simulator and the frontend. The frontend's nginx
already serves the dashboard and forwards `/api` to the backend, so no load balancer is needed. The
instance is meant to be **stopped between demos**.

```
internet --HTTP 80--> nginx (frontend container) --/api--> backend --> mysql
                                                              |-----> simulator (SOAP)
                                                              '-----> odoo --> odoo-db
                          all six containers on one host, one Docker network
```

| Choice | Why |
| --- | --- |
| EC2, not Fargate | Fargate has no free tier. An instance's hours are the cheapest compute here |
| No load balancer | nginx in the frontend image already does the routing. An ALB is about 16 USD a month |
| MySQL in a container, not RDS | One less always-on resource. RDS free tier is 12 months on legacy accounts only |
| No Elastic IP | A public IPv4 address is billed by the hour whether or not it is idle. The auto-assigned one is billed only while the instance runs, so **the address changes on every start** |
| No NAT gateway | About 32 USD a month. The instance sits in a public subnet instead |
| No SSH, no key pair | Session Manager reaches it with no open port |
| `prod` profile | No demo data, API docs closed, JSON logs |

## What your account actually is

An account created on **16 November 2026** is on the AWS **Free plan**, not the old 12-month free
tier. That means:

- 100 USD of credits at signup, up to 200 USD in total if you finish the console's activities.
- The plan lasts **six months, or until the credits are gone**, whichever comes first. The account
  is then suspended unless you move to the Paid plan.
- There is no "free tier eligible" gate to respect. Credits pay for whatever you use, so the only
  question is how much you use.
- Joining an AWS Organization upgrades the account to the Paid plan, and that cannot be undone.

### The region is not yours to pick

A Free plan account is a member of an AWS-run organization, and a **service control policy pins it
to one region**. On this account that region is **`ap-southeast-2` (Sydney)**; in `ap-south-1`
(Mumbai) every call to CloudFormation, EC2, ECR and SSM comes back as an explicit deny from policy
`p-hw6u0mkt` of organization `o-3gc08qkl75`. That is AWS's doing, not a misconfiguration, and it is
not something an administrator in your own account can change.

Find your own region by trying one call in each candidate region, as `free.sh` effectively does:

```bash
AWS_DEFAULT_REGION=ap-southeast-2 aws cloudformation list-stacks --max-items 1
```

From India this means about 130 ms of latency to the dashboard. It is fine for a demo.

What this deployment costs against those credits, in `ap-southeast-2`:

| Item | While running | While stopped |
| --- | --- | --- |
| `t3.small` instance (2 GB) | about 0.021 USD an hour | nothing |
| One public IPv4 address | about 0.005 USD an hour | nothing |
| 20 GB gp3 disk | about 1.80 USD a month | about 1.80 USD a month |
| ECR, about 350 MB of images | about 0.04 USD a month | the same |
| VPC, Parameter Store, Session Manager | nothing | nothing |

**A demo of two hours costs about 0.05 USD.** Thirty demo-hours in a month, plus the disk, is
roughly **2.60 USD a month** — about 1 % of the signup credits. Leaving it running all month would
be about 21 USD. Stop it when you are finished; that is the whole trick.

Prices change and this has not been checked against a bill. Put it in the
[AWS Pricing Calculator](https://calculator.aws/) if the number matters.

## Before you deploy

1. **Do not use the root user.** In IAM, create a user for yourself with `AdministratorAccess`,
   turn on MFA for both it and the root user, and create an access key for the new user only. The
   scripts refuse to run as root.

2. **Configure the CLI** (it is already installed here, version 2.40.2):

   ```bash
   aws configure
   ```

   Give it the access key, the secret, the region your account is pinned to (see above) and
   output `json`. Check it:

   ```bash
   aws sts get-caller-identity
   ```

3. **Set a budget first.** A budget warns; it does not stop anything. Replace the address:

   ```bash
   aws budgets create-budget --account-id "$(aws sts get-caller-identity --query Account --output text)" --budget '{"BudgetName":"finbridge","BudgetLimit":{"Amount":"10","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' --notifications-with-subscribers '[{"Notification":{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":50,"ThresholdType":"PERCENTAGE"},"Subscribers":[{"SubscriptionType":"EMAIL","Address":"you@example.com"}]}]'
   ```

4. **Install the Session Manager plugin**, which is how you reach Odoo and the API without opening
   a port: search "Session Manager plugin" in the AWS documentation and run the Windows installer.

5. **Check the stack still works locally.** The images are built from this repository, so if it is
   broken here it will be broken there.

   ```bash
   docker compose --profile app up -d --build
   ```

## Deploy

From Git Bash, in `deploy/aws`:

```bash
./free.sh deploy
```

It asks before creating anything billable, then does five things: creates the three ECR
repositories, builds and pushes the three images with a timestamp tag, generates the secrets into
Parameter Store, writes the instance's `.env` and Compose file as two parameters, and creates the
network, firewall, role and instance.

The first boot takes about 10 minutes: it installs Docker, makes a 2 GB swap file, pulls six
images, creates the Odoo database with Invoicing and Purchase, and replaces Odoo's initial
`admin/admin` password with a generated one. Watch it:

```bash
./free.sh logs
```

## Then, in this order

| # | Command or action | What it is for |
| --- | --- | --- |
| 1 | `./free.sh status` | The address, the instance state, which containers are up, how much memory is left |
| 2 | `./free.sh credentials` | Every sign-in for the deployed system: the dashboard address, the three users and Odoo's admin. Prints secrets to your terminal |
| 3 | Open `http://<the address>` | The dashboard. Sign in with that password |
| 4 | `./free.sh forward 8069`, then `http://localhost:8069` | Odoo. Sign in as `admin` with `./free.sh odoo-admin-password` |
| 5 | In Odoo: create vendors and **post** vendor bills | Nothing can be synchronized or paid until this exists |
| 6 | In Odoo: Settings > Users > create `finbridge-integration`, then Account Security > New API Key | The backend needs its own key; the cloud Odoo is a different instance from your local one |
| 7 | `./free.sh odoo-key <the key>` | Stores it and restarts the backend |
| 8 | Dashboard > Integrations > Run health check | Odoo, SOAP and REST should all answer |
| 9 | The demo, from [demo-script.md](demo-script.md) | |
| 10 | `./free.sh stop` | Stop paying for compute |

Odoo's API key cannot be created through the API, so steps 5 and 6 are by hand, exactly as they
were locally ([odoo.md](odoo.md)).

## Deploying again is not always safe

CloudFormation **replaces** an EC2 instance when its user data changes, and a replacement starts
from an empty disk: the FinBridge database and the whole Odoo database, its integration user and
API key included, would be gone. A new image tag alone does not cause this; a change to the boot
script in `free-tier.yaml` does.

`free.sh deploy` now creates a change set first and asks before going ahead when the instance
would be replaced. To look for yourself:

```bash
aws cloudformation create-change-set --stack-name finbridge-free --change-set-name check --template-body file://free-tier.yaml --capabilities CAPABILITY_IAM --parameters ParameterKey=ProjectName,ParameterValue=finbridge ParameterKey=InstanceType,ParameterValue=t3.small
```

Then read `Changes[].ResourceChange.Replacement` from `describe-change-set`. To pick up new images
without any of this risk, leave the template alone and restart the containers on the instance.

## Reaching Odoo without the Session Manager plugin

`free.sh forward` needs the plugin. Without it, open Odoo's port to your own address only:

```bash
aws ec2 authorize-security-group-ingress --group-id <the stack's security group> --protocol tcp --port 8069 --cidr <your IP>/32 --region ap-southeast-2
```

Find the group with `aws cloudformation describe-stack-resources --stack-name finbridge-free
--logical-resource-id InstanceSecurityGroup`, and your address with `curl https://checkip.amazonaws.com`.
**Close it again when you are finished**, with `revoke-security-group-ingress` and the same
arguments: a home address usually changes, and the rule would then let a stranger in.

## Between demos

```bash
./free.sh stop
```

```bash
./free.sh start
```

The data survives: MySQL and Odoo keep their volumes on the disk. Two things change on every start:

- **The address is new.** There is no Elastic IP, on purpose. Ask `./free.sh status` for it.
- **The simulator has forgotten every payment.** It keeps them in memory. Settled payments then
  appear as `MISSING_EXTERNAL_RECORD` in reconciliation, which is the simulator's limit, not a bug.

## When you are finished

```bash
./free.sh teardown
```

It asks first, then deletes the instance, its disk, the images, both stacks and every parameter
under `/finbridge/`. The MySQL data and the whole Odoo database go with it. Check the Billing page
the next day to be sure nothing is left.

## Memory: the thing most likely to bite

A `t3.small` has 2 GB. The Compose file's limits add up to 1908 MB, and the operating system wants
about 250 MB, so it is deliberately oversubscribed and relies on a 2 GB swap file. Limits are caps,
not reservations, and the containers idle well below them — but Odoo is the hungry one.

| Symptom | Fix |
| --- | --- |
| Odoo is very slow, or a container shows `Exited (137)` | 137 is the kernel killing it for memory. Redeploy with `FINBRIDGE_INSTANCE_TYPE=t3.medium ./free.sh deploy` (4 GB, about 0.042 USD an hour) |
| `free.sh status` shows little free memory and heavy swap | The same |

Odoo runs with `--workers=0` (one threaded process) and its own soft and hard memory limits below
the container's, so it recycles itself rather than being killed.

## Security of this deployment

| Concern | What is true here |
| --- | --- |
| HTTPS | **None.** There is no domain, so there is no certificate. Passwords and JWTs cross the internet in clear text. Use throwaway accounts and do not reuse a real password |
| Who can reach it | Port 80 from the whole internet by default. Narrow it with the `PublicCidr` parameter if only you need it |
| Odoo, the API and the simulator | Closed to the internet unless you widen `AdminCidr`. Reach them with `./free.sh forward` |
| Shell access | No SSH, no key pair, no open port. Session Manager only, which is logged by AWS |
| Secrets | Generated by the script, stored as SecureStrings, never written to a file on your machine and never printed except by `admin-password` and `odoo-admin-password`. The instance's role may read only `/finbridge/*` and pull only its own three images |
| Secrets in logs | Tracing is switched off around the part of the boot script that handles passwords, so `free.sh logs` cannot leak them |
| Rotation | Deploying again does **not** rotate the secrets, on purpose: MySQL's volume keeps the password it was created with |

### Changing a password afterwards

The two that people use can be changed safely at any time; the two that databases were created
with cannot.

| Password | How |
| --- | --- |
| Dashboard administrator | Sign in, Users, Reset password. Then `aws ssm put-parameter --name /finbridge/bootstrap-admin-password --type SecureString --overwrite --value file://<a file holding it>`, so `free.sh admin-password` stays true. Resetting it also ends every live session |
| Odoo administrator | Put the new value in `/finbridge/odoo-admin-password` first, then `./free.sh refresh-env`, then set it in Odoo (My Preferences, or the Odoo shell). Writing it to Parameter Store first keeps it out of any command history |
| `DB_PASSWORD`, `DB_ROOT_PASSWORD` | **Not by changing the parameter.** The MySQL volume holds the password it was created with, so the backend would simply be locked out. Change it inside MySQL with `ALTER USER` first |
| `ODOO_PG_PASSWORD` | The same trap, with the PostgreSQL volume |

Nothing is ever lost by forgetting a password: every one of them is readable from Parameter Store,
and `free.sh credentials` prints the lot.

### Users on the deployed system

A fresh deployment has **one** user, the administrator from `BOOTSTRAP_ADMIN_EMAIL`. The
`DEMO_...` accounts in a local `.env` are local only. To show that the roles actually bite, create
one user per role from the dashboard's Users page, or through the API as an administrator:

| Email | Role | Password parameter |
| --- | --- | --- |
| `admin@finbridge.local` | ADMIN | `/finbridge/bootstrap-admin-password` |
| `demo.finance@finbridge.local` | FINANCE_MANAGER | `/finbridge/demo-finance-password` |
| `demo.operator@finbridge.local` | INTEGRATION_OPERATOR | `/finbridge/demo-operator-password` |

Checked against the deployed backend on 16 November 2026, which is the role table of
[architecture.md](architecture.md) enforced for real:

| Endpoint | FINANCE_MANAGER | INTEGRATION_OPERATOR |
| --- | --- | --- |
| `GET /api/integrations/logs` | 403 | 200 |
| `GET /api/reconciliation` | 200 | 403 |
| `GET /api/users` | 403 | 403 |

## What the first real deployment found

Four defects that `cfn-lint`, `bash -n` and `docker compose config` all passed over. Each is fixed.

| Defect | Effect | Fix |
| --- | --- | --- |
| The image prefix was substituted twice | Every pull would have failed on `finbridge/finbridge-backend` | `render_compose` substitutes registry and project separately |
| `set -x` was on while the boot script read passwords | The Odoo admin and PostgreSQL passwords would have been written to a log that `free.sh logs` prints | Tracing is switched off around that block, and the variables are unset after |
| The Compose file was 4520 characters | A Standard tier SSM parameter holds 4096, so the parameter was never written and the stack never created | Comments are stripped on upload (3701), with a size guard that names the Advanced tier as the alternative |
| The Odoo step tested "does the database exist" | **Odoo creates that database itself on startup, holding only `base`.** The test passed, the module install was skipped, and there was no `account.move` to synchronize at all | The test is now whether `account` is installed |

Two smaller ones: `confirm` compared against a lowercase `yes`, so a capitalised answer stopped the
deployment; and the AWS CLI on Windows aborts on the non-ASCII characters in Docker's output, so
`on_instance` now strips them on the instance.

## What is measured, and what still is not

| Item | State |
| --- | --- |
| The stack on a real `t3.small` | Six containers healthy. 1913 MB total, 1253 MB used, 440 MB available with everything running and idle. The 2 GB swap file was not needed at idle |
| The dashboard | HTTP 200 on the public address, with `Content-Security-Policy`, `X-Content-Type-Options` and `X-Frame-Options` as locally |
| Login | An administrator logged in through nginx and received a JWT |
| SOAP and REST | `ok` in the health check, by service name inside the Docker network |
| Odoo | Modules `account` and `purchase` installed, admin password set from Parameter Store, integration user created with Billing and Purchase User rights, API key generated and stored. Health check `ok` |
| A synchronization in the cloud | Run 2 `COMPLETED`: 3 suppliers, 6 invoices created, 0 rejected, and the one draft bill correctly left behind. Vendor references arrived intact, including the duplicate pair |
| Test data in the cloud Odoo | Three `[TEST]` vendors, six posted bills in **INR** and one draft, created through the Odoo shell. The pair sharing `TEST-DUP-777` is the duplicate case |
| The currency | A fresh Odoo company defaults to **USD**, and Odoo refuses to change it once journal entries exist. Switching to INR on 16 November meant deleting every bill, activating INR (`res.currency` ships it inactive), setting the company currency and recreating the bills, plus clearing FinBridge's own tables so the re-sync did not duplicate them. **Set the currency before posting anything** |
| A payment in the cloud | `BILL/2026/11/0001`, 15000.00 INR, `SUCCESS` on attempt 1, external id `SIM-A45F5FE48ED44C97`. The SOAP call left the backend container and the simulator answered |
| Reconciliation in the cloud | 10 to 16 November: 3 records, 1 `MATCHED` and 2 `DUPLICATE`, the `TEST-DUP-777` pair. A run for today alone finds only the `MATCHED` one, because **the rules only look at invoices dated inside the period** and that pair is dated 12 and 13 November |
| Clearing the data | FinBridge's business tables can be emptied on the instance without touching `users`, `roles`, `user_roles`, `integration_configurations` or Flyway's history, so the sign-ins survive. The simulator keeps payments in memory, so restart it too or a re-paid invoice reference is refused with `IDEMPOTENCY_CONFLICT` |
| Every payment scenario | Run on the deployed instance on 16 November, all six as designed |
| Reconciliation statuses | Five of six produced on the deployed instance |
| The review workflow | A review without a note was refused `400 VALIDATION_FAILED`; with a note it was accepted `200` |
| The dashboard in a browser | Every route checked against the deployed instance on 16 November: dashboard tiles and charts, invoices, payments, reconciliation, integrations and settings, all with live data, INR in Indian digit grouping, secrets shown as "Configured" and never as values. **No console errors at all**, so the Content-Security-Policy holds in the cloud as it does locally |

Payment scenarios, on the cloud instance:

| Scenario | What happened |
| --- | --- |
| `SLOW_SUCCESS` | `TIMEOUT_UNKNOWN` after **10.6 s**, then a status check resolved it to `SUCCESS` **still on attempt 1** — the provider was asked, not paid again |
| `FAILED` | `FAILED` with `PROCESSING_ERROR`; an operator retry gave `SUCCESS` as **attempt 2** |
| `PENDING_THEN_SUCCESS` | `PENDING`, still `PENDING` after the first status check, `SUCCESS` after the second |
| `SHORT_PAY` | `SUCCESS`, 29700.00 settled against 33000.00 requested |
| `OUTAGE` | `FAILED` with `SERVICE_UNAVAILABLE`, nothing recorded at the provider |
| `REJECTED` | `REJECTED` with `ACCOUNT_BLOCKED`; a further retry was refused `422 BUSINESS_RULE_VIOLATION` |

Reconciliation statuses, from the same data:

| Status | How it arose |
| --- | --- |
| `MATCHED` | Three payments agreeing with the statement |
| `MISMATCH` | "amount: FinBridge requested 33000, payment service settled 29700 (difference 3300)" |
| `DUPLICATE` | The `TEST-DUP-777` pair |
| `MISSING_EXTERNAL_RECORD` | A payment made before the instance restarted, which the simulator forgot |
| `MISSING_INTERNAL_RECORD` | An orphan settlement posted with `POST /sim/orphans` |
| `PENDING_REVIEW` | A `PENDING_THEN_SUCCESS` payment left without a status check: "Both sides agree that the payment is PENDING. It is not settled, so someone should follow it up" |

**All six statuses have now been produced on the deployed system.**

One thing the run showed that the rule table implies but does not spell out: **`DUPLICATE` masks
`MISMATCH`**. The first short-paid invoice was one of the duplicate pair, so rule 1 won and no
mismatch appeared. A clean invoice had to be posted to see rule 4 at all.
| `free.sh stop` and `start` | Both run on 16 November. The instance stopped, started again, and **everything came back by itself**: six containers via the systemd unit, all six INR invoices with the paid one still paid, and all three integrations healthy |
| The address change | Proven: the public address was different after one stop and start, as designed. `free.sh status` reports the current one |
| How long a restart takes | nginx answers HTTP 200 within about a minute, but `/api` returns **502 until the backend finishes starting**, roughly 40 seconds later. That is the backend booting, not a fault |
| `free.sh teardown` | **Not yet run** |
| Memory under load | Not measured. Idle only |
| Cost | Calculated from public prices, not yet compared with a bill |
