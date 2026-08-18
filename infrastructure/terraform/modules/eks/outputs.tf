output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_arn" {
  value = aws_eks_cluster.this.arn
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority_data" {
  value     = aws_eks_cluster.this.certificate_authority[0].data
  sensitive = true
}

output "cluster_security_group_id" {
  value = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "cluster_version" {
  value = aws_eks_cluster.this.version
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}

output "cluster_role_arn" {
  value = aws_iam_role.cluster.arn
}

output "node_group_name" {
  value = aws_eks_node_group.workloads.node_group_name
}
