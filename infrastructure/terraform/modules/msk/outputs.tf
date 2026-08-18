output "cluster_arn" {
  value = aws_msk_cluster.this.arn
}

output "cluster_name" {
  value = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_sasl_scram" {
  description = "TLS SASL/SCRAM bootstrap brokers. Map to USAGECORE_KAFKA_BOOTSTRAP_SERVERS after apply."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "zookeeper_connect_string" {
  description = "Provided by the AWS API; UsageCore uses KRaft locally and Kafka clients should use bootstrap brokers."
  value       = aws_msk_cluster.this.zookeeper_connect_string
}
