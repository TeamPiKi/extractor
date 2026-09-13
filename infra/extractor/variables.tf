variable "name_prefix" {
  description = "리소스/태그 이름 접두사"
  type        = string
  default     = "piki-extractor"
}

# ---------------------------------------------------------------------------
# 기존 piki 인프라 참조 (data 소스 조회 키) — resource 참조가 아니라 읽기 전용.
# 값은 piki repo 의 terraform 코드(vpc.tf · security_groups.tf)에서 파생된 것.
# ---------------------------------------------------------------------------
variable "vpc_cidr" {
  description = "piki VPC CIDR (data.aws_vpc 조회 키). piki/terraform vpc.tf 의 var.vpc_cidr 과 일치."
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "박스를 둘 public subnet CIDR (data.aws_subnet 조회 키). piki vpc.tf 의 public_subnet_cidr 과 일치."
  type        = string
  default     = "10.0.1.0/24"
}

variable "app_sg_name" {
  description = <<-EOT
    piki 앱 EC2 보안그룹 이름. 이 SG 에서만 추출 API 포트 인바운드를 허용한다(외부 노출 0).
    본 서버 dev/staging/prod 세 인스턴스가 이 SG 하나를 공유하므로 규칙 하나로 세 호출자를 다 덮는다.
    ※ 참조 계약: piki 팀이 이 SG 를 rename/재생성하면 이 모듈 plan 이 깨진다. 라이브 이름은 plan 으로 확정할 것.
  EOT
  type        = string
  default     = "team3-dev-ec2-sg"
}

# ---------------------------------------------------------------------------
# 박스 스펙
# ---------------------------------------------------------------------------
variable "extractor_port" {
  description = "추출 API 가 listen 하는 포트(앱→박스). 서비스 구현·배포는 앱 레이어 소관, 여기선 SG 인바운드만 연다."
  type        = number
  default     = 8090
}

variable "instance_type" {
  description = <<-EOT
    ARM(Graviton) 인스턴스 타입 — JVM 서비스라 arm64 가 가능하고 저렴하다(본 서버 prod 와 같은 t4g.small=2GiB).
    분리 동기가 파싱의 메모리 스파이크(LLM 대기·3MB HTML·이미지) 격리이므로 micro(1GiB)로 내리지 않는다.
  EOT
  type        = string
  default     = "t4g.small"
}

variable "ami_id" {
  description = <<-EOT
    고정 AMI ID. null 이면 data.aws_ami 로 최신 arm64 Ubuntu 24.04 를 조회한다.
    최초 apply(2026-07-07) 후 조회된 AMI 를 여기 고정했다 — 새 AMI 공개 때 apply 가 인스턴스를
    교체(파괴+재생성)하는 걸 막는다. AMI ID 는 region 별 public 값이라 커밋해도 안전(tfvars 는 gitignore 라
    이 default 에 둬야 다른 사람 apply 에서도 교체가 안 난다). 의도적 재프로비저닝 때만 값을 바꾼다.
  EOT
  type        = string
  default     = "ami-0cac45084c40c4162"
}

variable "root_volume_size" {
  description = <<-EOT
    루트 EBS(gp3) 크기(GB). 배포 게이트(blocks/prune_images.sh)가 요구하는 여유 10GB 를 남기려면
    "실사용 최대 + 10GB" 가 파일시스템 가용 안에 들어야 한다. 이 박스의 실사용 최대는 6.0GB 로
    네 앱 박스 중 가장 작지만, 게이트 값을 하나로 두려고 같은 25GB 로 맞춘다
    (TeamPiKi/infra#94).
  EOT
  type        = number
  default     = 25
}

# ---------------------------------------------------------------------------
# 근미래(이관 6단계 — 이미지 OCR 경로) 대비: S3 이미지 버킷 접근.
# 지금은 null(정책 미생성). 6단계 착수 때 버킷 이름을 tfvars 로 넣으면 읽기/쓰기 정책이 생긴다.
# ---------------------------------------------------------------------------
variable "images_bucket_name" {
  description = <<-EOT
    상품 이미지 S3 버킷 이름(raw 읽기 + 크롭 결과 쓰기). null 이면 정책을 만들지 않는다.
    extractor 는 dev/staging/prod 세 환경 트래픽을 받고 각 환경 버킷이 다르므로, IAM 리소스에
    와일드카드를 쓴다 — 예: "*piki-images-<ACCOUNT_ID>" 를 넣으면 dev-piki-images-*·staging-*·piki-images-* 를 모두 덮는다.
    account id 가 들어가 public repo 에 커밋하지 않는다 — apply 시 -var 또는 gitignore 된 tfvars 로 주입한다.
  EOT
  type        = string
  default     = null
}
