# Gateway endpoint is not billed hourly. Interface endpoints (ECR API/DKR, Secrets Manager,
# CloudWatch Logs, STS) are omitted in this dev topology to limit cost; see docs/aws.
resource "aws_vpc_endpoint" "s3" {
  count = var.enable_s3_gateway_endpoint ? 1 : 0

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    [aws_route_table.public.id],
    aws_route_table.private_app[*].id,
  )

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-s3"
  })
}

data "aws_region" "current" {}
