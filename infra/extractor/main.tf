# =============================================================================
# piki-extractor: 상품 추출(fetch·구조화 파싱·LLM) 전용 단일 서비스 박스 (prod).
#
# 이 terraform 은 **인프라만** 소유한다 — EC2 · EIP · SG · IAM · 하드닝.
# 추출 서비스(Java/Spring) 코드·런타임·배포는 앱 레이어 소관이며 여기 안 박는다
# (user_data 는 Docker 베이스까지만 → JVM/앱 버전이 바뀌어도 terraform 무변경).
#
# dev 는 전용 박스가 없다 — 본 서버 dev 박스에 컨테이너로 동거한다(2환경 비대칭, 설계 결정).
# 본 서버 staging 은 이 prod 박스를 바라본다(배포 순서 규율 검증용).
# =============================================================================

# --- 기존 piki 인프라: 읽기만(독립 state 라 resource 참조 불가, data 소스로 조회) ---
data "aws_vpc" "main" {
  filter {
    name   = "cidr-block"
    values = [var.vpc_cidr]
  }
}

data "aws_subnet" "public" {
  vpc_id = data.aws_vpc.main.id
  filter {
    name   = "cidr-block"
    values = [var.public_subnet_cidr]
  }
}

# piki 앱 SG — 인바운드를 '우리 앱 서버에서만' 으로 묶기 위해 IP 대신 SG id 참조.
data "aws_security_group" "app" {
  vpc_id = data.aws_vpc.main.id
  name   = var.app_sg_name
}

# 최신 arm64 Ubuntu 24.04 (var.ami_id 지정 시 그 값 사용). JVM 서비스라 Graviton(arm64) 사용 — 본 서버와 동일 라인.
data "aws_ami" "ubuntu" {
  count       = var.ami_id == null ? 1 : 0
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# --- IAM: SSM(관리·비상 접근) + 시크릿을 SSM Param 에서 읽기. 배포는 SSH(단일 transport)로 통일 ---
resource "aws_iam_role" "extractor" {
  name = var.name_prefix
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "extractor_ssm" {
  role       = aws_iam_role.extractor.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# GEMINI API 키 등 시크릿은 SSM Parameter Store(/piki-extractor/*, SecureString)에서만 읽는다 —
# 박스/코드/env 파일 하드코딩 금지 (piki-headless-browser 와 같은 규율).
# 파라미터 생성(예: /piki-extractor/gemini-api-key)은 콘솔/CLI 수동 — 시크릿 값은 terraform state 에 안 남긴다.
resource "aws_iam_role_policy" "extractor_params" {
  name = "${var.name_prefix}-ssm-params"
  role = aws_iam_role.extractor.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
      # 관측 자격은 세 서비스가 공유 경로 하나를 읽는다(회전 1곳화, TeamPiKi/core#771).
      Resource = [
        "arn:aws:ssm:ap-northeast-2:*:parameter/${var.name_prefix}/*",
        "arn:aws:ssm:ap-northeast-2:*:parameter/piki/observability/*",
      ]
    }]
  })
}

# 이관 6단계(이미지 OCR 경로) 대비 — images_bucket_name 이 주어질 때만 생성된다.
# raw 원본 읽기(items/raw/*) + 크롭 결과 쓰기(items/*). 버킷 관리(수명주기·정책)는 piki 앱 state 소관이라 여기선 접근권만.
resource "aws_iam_role_policy" "extractor_images" {
  count = var.images_bucket_name == null ? 0 : 1
  name  = "${var.name_prefix}-images"
  role  = aws_iam_role.extractor.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject"]
      Resource = "arn:aws:s3:::${var.images_bucket_name}/items/*"
    }]
  })
}

resource "aws_iam_instance_profile" "extractor" {
  name = var.name_prefix
  role = aws_iam_role.extractor.name
}

# --- SG: inbound = piki app SG 에서 추출 포트만, egress = 전체(임의 상품 링크 fetch + Gemini + 헤드리스) ---
resource "aws_security_group" "extractor" {
  name        = var.name_prefix
  description = "piki extractor: inbound only from app SG on extractor port, egress all"
  vpc_id      = data.aws_vpc.main.id

  ingress {
    description     = "extraction API from piki app servers only"
    from_port       = var.extractor_port
    to_port         = var.extractor_port
    protocol        = "tcp"
    security_groups = [data.aws_security_group.app.id] # IP 대신 SG 참조 = '우리 서버만'
  }

  # SSH 단일 transport 결정(배포 블록 공통화): 배포가 core 와 같은 "러너에서 SSH" 경로로 통일된다.
  # GH 러너는 고정 IP 가 없어 전역 개방. 인증은 키 전용(비밀번호 로그인 없음)이라 실질 위험은
  # sshd 자체 취약점 노출면이며, 이는 core 박스가 이미 수용 중인 것과 같은 등급이다.
  ingress {
    description = "SSH for deploy (GitHub Actions runner) and ops"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "all outbound (arbitrary product links + Gemini API + headless render)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = var.name_prefix }
}

# --- 고정 public IP: 몰 fetch egress IP 고정(차단 대응 시 IP 평판 관리) + 앱은 private IP 로 접근 ---
resource "aws_eip" "extractor" {
  domain = "vpc"
  tags   = { Name = var.name_prefix }
}

resource "aws_instance" "extractor" {
  ami                    = var.ami_id != null ? var.ami_id : data.aws_ami.ubuntu[0].id
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.public.id
  iam_instance_profile   = aws_iam_instance_profile.extractor.name
  vpc_security_group_ids = [aws_security_group.extractor.id]

  metadata_options {
    http_tokens                 = "required" # IMDSv2 강제
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size
    encrypted   = true
  }

  # 얇은 베이스: Docker 만 설치(arm64). 추출 서비스 컨테이너는 terraform 이 안 건드리고
  # 앱 배포 레이어가 올린다(→ JVM/앱 버전이 바뀌어도 이 terraform 무변경). SSH 공개키는 terraform 밖에서 주입(authorized_keys).
  user_data = <<-EOF
    #!/usr/bin/env bash
    set -euxo pipefail
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
    apt-get update -y
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker ubuntu
    systemctl enable --now docker
  EOF

  tags = { Name = var.name_prefix }
}

resource "aws_eip_association" "extractor" {
  instance_id   = aws_instance.extractor.id
  allocation_id = aws_eip.extractor.id
}

output "instance_id" { value = aws_instance.extractor.id }
output "private_ip" { value = aws_instance.extractor.private_ip } # 앱→박스 호출용 (EXTRACT_REMOTE_BASE_URL)
output "public_ip" { value = aws_eip.extractor.public_ip }        # 몰 fetch egress IP
