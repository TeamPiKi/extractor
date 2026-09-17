# OIDC provider 는 계정당 하나라 core terraform 이 소유한다. 여기서는 참조만.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_push_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # 이 repo 는 GitHub Environment 가 없어 main 브랜치 ref 로 좁힌다. workflow_run·dispatch 모두 main 에서 돈다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_ecr_push" {
  name               = "${var.name_prefix}-gha-ecr-push"
  assume_role_policy = data.aws_iam_policy_document.github_push_assume.json
}

data "aws_iam_policy_document" "ecr_push" {
  statement {
    sid       = "AuthToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "PushToExtractor"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.extractor.arn]
  }
}

resource "aws_iam_role_policy" "github_ecr_push" {
  name   = "${var.name_prefix}-gha-ecr-push"
  role   = aws_iam_role.github_ecr_push.id
  policy = data.aws_iam_policy_document.ecr_push.json
}

# deploy.yml 의 vars.AWS_ECR_PUSH_ROLE_ARN 에 사람이 넣는다.
output "github_ecr_push_role_arn" { value = aws_iam_role.github_ecr_push.arn }
