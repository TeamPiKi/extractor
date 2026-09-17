# 이미지 저장소. push 는 러너가 OIDC 로(oidc.tf), pull 은 박스 instance role 로. core 와 같은 구조(core#860).
resource "aws_ecr_repository" "extractor" {
  name                 = "piki-extractor"
  image_tag_mutability = "MUTABLE" # latest 태그를 매 배포 재부착

  image_scanning_configuration {
    scan_on_push = true
  }
}

# SHA 태그가 커밋마다 쌓인다. Docker Hub 에는 이 정리 정책이 없었다.
resource "aws_ecr_lifecycle_policy" "extractor" {
  repository = aws_ecr_repository.extractor.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "최신 30개 이미지만 유지, 초과분 삭제"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      }
    ]
  })
}

# GetAuthorizationToken 만 리소스 * 를 요구한다(ECR 사양).
data "aws_iam_policy_document" "ecr_pull" {
  statement {
    sid       = "AuthToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "PullExtractor"
    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchCheckLayerAvailability",
    ]
    resources = [aws_ecr_repository.extractor.arn]
  }
}

resource "aws_iam_role_policy" "extractor_ecr_pull" {
  name   = "${var.name_prefix}-ecr-pull"
  role   = aws_iam_role.extractor.id
  policy = data.aws_iam_policy_document.ecr_pull.json
}

output "ecr_repository_url" { value = aws_ecr_repository.extractor.repository_url }
