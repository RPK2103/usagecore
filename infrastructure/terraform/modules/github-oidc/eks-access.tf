# IAM permission (this module) is separate from Kubernetes authorization.
# EKS access entries grant the Helm deploy role namespaced admin in usagecore
# (and kube-system for the AWS Load Balancer Controller bootstrap).
# This is not cluster-admin for the whole API.

resource "aws_eks_access_entry" "helm_deploy" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.helm_deploy.arn
  type          = "STANDARD"
  tags          = var.tags
}

resource "aws_eks_access_policy_association" "helm_deploy_app" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.helm_deploy.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSAdminPolicy"

  access_scope {
    type       = "namespace"
    namespaces = [var.kubernetes_namespace, "kube-system"]
  }

  depends_on = [aws_eks_access_entry.helm_deploy]
}

resource "aws_eks_access_entry" "terraform_apply" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.terraform_apply.arn
  type          = "STANDARD"
  tags          = var.tags
}

# Terraform apply does not install application Helm releases. Cluster view is
# enough for EKS resource refresh; Kubernetes object mutation stays on helm-deploy.
resource "aws_eks_access_policy_association" "terraform_apply_view" {
  cluster_name  = var.eks_cluster_name
  principal_arn = aws_iam_role.terraform_apply.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.terraform_apply]
}
