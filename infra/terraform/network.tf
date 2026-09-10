# The network, and the one design decision in this file that everything else follows from:
#
#   THERE IS NO NAT GATEWAY.
#
# A NAT Gateway is about USD 33 per month before a byte crosses it, plus USD 0.045 per GB. For a
# stack whose entire point is to be affordable to leave running, that single resource would cost
# more than everything else here combined.
#
# The consequence is that Fargate tasks needing egress — pulling from ECR, calling Bedrock or the
# Gemini API, shipping logs — run in public subnets with a public IP. That is a real trade-off and
# not a free lunch: the tasks are addressable from the internet at the IP level, and the only thing
# standing between them and the world is the security group. Which is why admin_cidr defaults to
# empty and creates no ingress rule at all.
#
# The alternative that keeps tasks private is interface VPC endpoints (ECR API, ECR DKR, S3
# gateway, CloudWatch Logs, Secrets Manager, and Bedrock). That is five interface endpoints at
# roughly USD 7.20 each per month, so about USD 36 — more than the NAT Gateway it was avoiding, and
# far more than the demo it is protecting. Documented in docs/cost.md rather than silently chosen.
#
# The database gets no route to the internet at all. Its subnets have no route table association
# beyond the VPC-local default, so there is no path out even if something in the DB security group
# were misconfigured.

resource "aws_vpc" "this" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = local.name
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = local.name
  }
}

# ---------------------------------------------------------------------------------------------
# Public subnets: Fargate tasks
# ---------------------------------------------------------------------------------------------

resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(aws_vpc.this.cidr_block, 8, count.index)
  availability_zone = local.azs[count.index]

  # Public IPs are assigned by the ECS service, not by the subnet. Anything else launched here has
  # to ask for one, which keeps the decision at the resource that needs it.
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name}-public-${local.azs[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${local.name}-public"
  }
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ---------------------------------------------------------------------------------------------
# Isolated subnets: the database
# ---------------------------------------------------------------------------------------------

resource "aws_subnet" "database" {
  count = length(local.azs)

  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(aws_vpc.this.cidr_block, 8, count.index + 10)
  availability_zone = local.azs[count.index]

  tags = {
    Name = "${local.name}-db-${local.azs[count.index]}"
    Tier = "isolated"
  }
}

# An explicit route table with no routes in it, rather than relying on the VPC's main route table.
# The main table is shared and can be edited by anything; an empty table owned by this stack means
# "no egress" is a property of the resource rather than an assumption about the account.
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${local.name}-db"
  }
}

resource "aws_route_table_association" "database" {
  count = length(aws_subnet.database)

  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id
}

# ---------------------------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  count = var.enable_alb ? 1 : 0

  name        = "${local.name}-alb"
  description = "Load balancer for the Commander console"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-alb"
  }
}

resource "aws_security_group" "commander" {
  name        = "${local.name}-commander"
  description = "Incident Commander task"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-commander"
  }
}

resource "aws_security_group" "demo_target" {
  name        = "${local.name}-demo-target"
  description = "Fault-injectable demo target service"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-demo-target"
  }
}

resource "aws_security_group" "database" {
  name        = "${local.name}-db"
  description = "PostgreSQL. Reachable from the Commander task and from nothing else."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${local.name}-db"
  }
}

# Egress. Written as separate rules rather than inline blocks so that a later edit to one rule
# cannot silently replace the whole set.
resource "aws_vpc_security_group_egress_rule" "commander_all" {
  security_group_id = aws_security_group.commander.id
  description       = "ECR, CloudWatch, Secrets Manager, the model provider, and the demo target"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "demo_target_all" {
  security_group_id = aws_security_group.demo_target.id
  description       = "ECR, CloudWatch, and PostgreSQL"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_commander" {
  count = var.enable_alb ? 1 : 0

  security_group_id            = aws_security_group.alb[0].id
  description                  = "Forward to the Commander task"
  referenced_security_group_id = aws_security_group.commander.id
  from_port                    = local.commander_port
  to_port                      = local.commander_port
  ip_protocol                  = "tcp"
}

# The database has no egress rule of any kind. PostgreSQL never initiates a connection here, and an
# "allow all outbound" on a database is the difference between a data-exposure bug and a
# data-exfiltration one.

# Ingress.
resource "aws_vpc_security_group_ingress_rule" "alb_public" {
  count = var.enable_alb && var.admin_cidr != "" ? 1 : 0

  security_group_id = aws_security_group.alb[0].id
  description       = "Operator access to the console"
  cidr_ipv4         = var.admin_cidr
  from_port         = var.certificate_arn == "" ? 80 : 443
  to_port           = var.certificate_arn == "" ? 80 : 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http_redirect" {
  count = var.enable_alb && var.admin_cidr != "" && var.certificate_arn != "" ? 1 : 0

  security_group_id = aws_security_group.alb[0].id
  description       = "Port 80, to redirect to HTTPS"
  cidr_ipv4         = var.admin_cidr
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "commander_from_alb" {
  count = var.enable_alb ? 1 : 0

  security_group_id            = aws_security_group.commander.id
  description                  = "From the load balancer only"
  referenced_security_group_id = aws_security_group.alb[0].id
  from_port                    = local.commander_port
  to_port                      = local.commander_port
  ip_protocol                  = "tcp"
}

# Direct access to the task IP, for the no-ALB deployment. Conditional on admin_cidr being set,
# which it is not by default: with no ALB and no admin_cidr, the Commander is reachable from
# nothing, and that is a working state rather than a broken one — investigations are started by the
# alert path, not by a human browsing to it.
resource "aws_vpc_security_group_ingress_rule" "commander_from_admin" {
  count = !var.enable_alb && var.admin_cidr != "" ? 1 : 0

  security_group_id = aws_security_group.commander.id
  description       = "Operator access directly to the task"
  cidr_ipv4         = var.admin_cidr
  from_port         = local.commander_port
  to_port           = local.commander_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "demo_target_from_commander" {
  security_group_id            = aws_security_group.demo_target.id
  description                  = "Health probes and fault deactivation from the Commander"
  referenced_security_group_id = aws_security_group.commander.id
  from_port                    = local.demo_target_port
  to_port                      = local.demo_target_port
  ip_protocol                  = "tcp"
}

# The traffic generator and the fault-injection API are driven from an operator machine, so this
# one is genuinely needed to run the demo — and it is exactly the rule to remove afterwards.
resource "aws_vpc_security_group_ingress_rule" "demo_target_from_admin" {
  count = var.admin_cidr != "" ? 1 : 0

  security_group_id = aws_security_group.demo_target.id
  description       = "Traffic generation and fault injection from an operator machine"
  cidr_ipv4         = var.admin_cidr
  from_port         = local.demo_target_port
  to_port           = local.demo_target_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "db_from_commander" {
  security_group_id            = aws_security_group.database.id
  description                  = "Incident state, sessions, artifacts and the audit log"
  referenced_security_group_id = aws_security_group.commander.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# There is deliberately no rule from the demo target to the database. Locally it gets its own
# database so that the connection-pool-pressure scenario cannot starve the investigator; on AWS it
# runs with no datasource at all rather than sharing the Commander's instance. The cost of a second
# RDS instance is not worth one of nine scenarios, and sharing the first one would make that
# scenario lie. See docs/deployment.md.
