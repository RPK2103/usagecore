output "vpc_id" {
  value = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "availability_zones" {
  value = var.availability_zones
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_app_subnet_ids" {
  value = aws_subnet.private_app[*].id
}

output "private_data_subnet_ids" {
  value = aws_subnet.private_data[*].id
}

output "nat_gateway_ids" {
  value = aws_nat_gateway.this[*].id
}

output "nat_gateway_strategy" {
  value = var.nat_gateway_strategy
}

output "s3_gateway_endpoint_id" {
  value = try(aws_vpc_endpoint.s3[0].id, null)
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "eks_nodes_security_group_id" {
  value = aws_security_group.eks_nodes.id
}

output "rds_security_group_id" {
  value = aws_security_group.rds.id
}

output "msk_security_group_id" {
  value = aws_security_group.msk.id
}
