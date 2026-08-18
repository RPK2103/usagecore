resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = var.security_group_ids

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
      }
    }
  }

  encryption_info {
    # AWS-managed MSK CMK (alias/aws/kafka). A customer CMK is a production cost/ops upgrade.
    encryption_at_rest_kms_key_arn = var.encryption_at_rest_kms_key_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      scram = true
      iam   = false
    }

    unauthenticated = false
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled = false
      }
    }
  }

  tags = var.tags
}

resource "aws_msk_scram_secret_association" "this" {
  count = length(var.scram_secret_arns) > 0 ? 1 : 0

  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = var.scram_secret_arns
}
