variable "name_prefix" {
  type        = string
  description = "Resource name prefix used in descriptions and tags. MSK secret names must use the AmazonMSK_ prefix."
}

variable "tags" {
  type = map(string)
}
