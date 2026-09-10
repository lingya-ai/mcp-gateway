# 在仓库根目录执行 docker buildx bake -f docker-bake.hcl。
variable "VERSION" { default = "0.0.3" }
variable "IMAGE_REPOSITORY" { default = "mcp-gateway" }
group "default" { targets = ["base", "node", "uv"] }
target "common" {
  context = "."
  dockerfile = "Dockerfile"
  platforms = ["linux/amd64"]
  args = { VERSION = VERSION }
}
target "base" {
  inherits = ["common"]
  target = "base"
  tags = ["${IMAGE_REPOSITORY}:${VERSION}", "${IMAGE_REPOSITORY}:${VERSION}-base"]
}
target "node" {
  inherits = ["common"]
  target = "node"
  tags = ["${IMAGE_REPOSITORY}:${VERSION}-node"]
}
target "uv" {
  inherits = ["common"]
  target = "uv"
  tags = ["${IMAGE_REPOSITORY}:${VERSION}-uv"]
}
