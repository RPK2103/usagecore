resource "aws_launch_template" "nodes" {
  name_prefix = "${var.name_prefix}-eks-nodes-"

  vpc_security_group_ids = concat(
    [aws_eks_cluster.this.vpc_config[0].cluster_security_group_id],
    var.additional_security_group_ids,
  )

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size           = var.node_disk_size
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  tag_specifications {
    resource_type = "instance"
    tags = merge(var.tags, {
      Name = "${var.name_prefix}-eks-node"
    })
  }

  tag_specifications {
    resource_type = "volume"
    tags          = var.tags
  }

  tags = var.tags
}

resource "aws_eks_node_group" "workloads" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name_prefix}-workloads"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.subnet_ids
  instance_types  = var.node_instance_types
  ami_type        = var.node_ami_type
  capacity_type   = var.node_capacity_type

  labels = {
    workload = "usagecore-workloads"
  }

  scaling_config {
    desired_size = var.node_desired_size
    min_size     = var.node_min_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  launch_template {
    id      = aws_launch_template.nodes.id
    version = aws_launch_template.nodes.latest_version
  }

  tags = var.tags

  depends_on = [
    aws_iam_role_policy_attachment.node_worker,
    aws_iam_role_policy_attachment.node_cni,
    aws_iam_role_policy_attachment.node_ecr_readonly,
    aws_iam_role_policy_attachment.node_ssm,
    aws_eks_access_entry.nodes,
  ]
}
