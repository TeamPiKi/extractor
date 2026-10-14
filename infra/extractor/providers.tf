# piki-extractor — 독립 terraform 루트 모듈 (piki-headless-browser infra 와 같은 구조).
#
# piki 앱의 통합 state(dev/staging/prod)와 분리된 자기 state 를 가진다.
# 같은 S3 버킷을 재사용하되 key 를 달리해(extractor/…) versioning·lock·암호화는
# 그대로 쓰면서 앱 state 와 blast radius 를 완전히 격리한다.
# → 이 모듈의 apply/destroy 는 piki 앱 prod/dev/staging 인프라를 절대 건드릴 수 없다.

terraform {
  required_version = ">= 1.14"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    http = {
      source  = "hashicorp/http"
      version = "~> 3.4"
    }
  }

  backend "s3" {
    # bucket 은 계정번호를 포함하므로 코드에 넣지 않는다(퍼블릭 repo).
    # 값은 gitignore 된 backend.hcl 로 주입: terraform init -backend-config=backend.hcl
    # (backend.hcl.example 참고.)
    key          = "extractor/terraform.tfstate" # 앱 state 와 분리
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      Project   = "piki"
      Component = "extractor"
      ManagedBy = "terraform"
    }
  }
}
