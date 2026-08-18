resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Internet-facing ALB for UsageCore HTTP APIs"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb"
  })
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each = var.alb_http_enabled ? toset(var.alb_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.alb.id
  description       = "HTTP from configured CIDRs"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = each.value

  tags = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = var.alb_https_enabled ? toset(var.alb_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from configured CIDRs"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = each.value

  tags = var.tags
}

resource "aws_vpc_security_group_egress_rule" "alb_to_apps" {
  for_each = toset([for port in var.app_ports : tostring(port)])

  security_group_id = aws_security_group.alb.id
  description       = "Forward to UsageCore workloads in the VPC"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  cidr_ipv4         = var.vpc_cidr

  tags = var.tags
}

resource "aws_security_group" "eks_nodes" {
  name        = "${var.name_prefix}-eks-nodes"
  description = "Additional security group for EKS managed nodes and pod ENIs"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-eks-nodes"
  })
}

resource "aws_vpc_security_group_ingress_rule" "nodes_from_alb" {
  for_each = toset([for port in var.app_ports : tostring(port)])

  security_group_id            = aws_security_group.eks_nodes.id
  description                  = "ALB to application ports"
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  referenced_security_group_id = aws_security_group.alb.id

  tags = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "nodes_from_self" {
  security_group_id            = aws_security_group.eks_nodes.id
  description                  = "Pod-to-pod traffic within the node security group"
  ip_protocol                  = "-1"
  referenced_security_group_id = aws_security_group.eks_nodes.id

  tags = var.tags
}

resource "aws_vpc_security_group_egress_rule" "nodes_all" {
  security_group_id = aws_security_group.eks_nodes.id
  description       = "Nodes require NAT/S3 endpoint egress for images and AWS APIs. Not a claim of pod NetworkPolicy isolation."
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"

  tags = var.tags
}

resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds"
  description = "RDS PostgreSQL; not publicly accessible"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds"
  })
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_nodes" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from EKS node/pod networking boundary"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.eks_nodes.id

  tags = var.tags
}

resource "aws_security_group" "msk" {
  name        = "${var.name_prefix}-msk"
  description = "MSK Kafka; not publicly accessible"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-msk"
  })
}

resource "aws_vpc_security_group_ingress_rule" "msk_sasl_scram_from_nodes" {
  security_group_id            = aws_security_group.msk.id
  description                  = "Kafka SASL/SCRAM TLS from EKS node/pod networking boundary"
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
  referenced_security_group_id = aws_security_group.eks_nodes.id

  tags = var.tags
}

resource "aws_vpc_security_group_egress_rule" "msk_broker_to_broker" {
  security_group_id            = aws_security_group.msk.id
  description                  = "Broker-to-broker communication"
  ip_protocol                  = "-1"
  referenced_security_group_id = aws_security_group.msk.id

  tags = var.tags
}
