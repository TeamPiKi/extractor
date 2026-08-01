#!/usr/bin/env bash
#
# extractor prod 박스 관측(Alloy) 배선 - 이 스크립트가 값의 SSOT다.
#
# TeamPiKi/infra 의 공통 Alloy 블록(blocks/alloy/config.alloy + provision-alloy.sh)을
# fetch 하고, Grafana Cloud 자격을 SSM Parameter Store 에서 읽어 env 로 얹은 뒤
# extractor 의 값(environment=prod, box=piki-extractor)으로 공통 블록을 호출한다.
#
# 실행 위치: extractor 박스 안 (SSM run-command 또는 세션 접속 후 직접 실행).
# 이 박스에는 aws cli 가 없어 SSM 조회는 dockerized aws cli 로 한다
# (인스턴스 IAM 롤 + IMDSv2 hop limit 2 라 컨테이너 안에서도 자격 획득 가능).
#
# 전제: TeamPiKi/infra 의 공통 Alloy 블록 PR 이 머지돼 있어야 한다. 머지 전에는
# --ref 로 그 PR 의 브랜치명을 지정해 선검증할 수 있다.
#
# 사용 예:
#   provision-observability.sh                      # main(머지된 공통 블록) 사용
#   provision-observability.sh --ref feat/alloy-common-block   # 머지 전 브랜치로 선검증
#
# 인자:
#   --ref   (선택) TeamPiKi/infra git ref. 기본 main
#
# 종료 코드: 성공 0, fetch/SSM 조회/공통 블록 실행 실패 1, 인자 오류 2

set -euo pipefail

REF="main"

while [ $# -gt 0 ]; do
  case "$1" in
    --ref) REF="${2:-}"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[ -n "$REF" ] || { echo "--ref 값이 비었다" >&2; exit 2; }

WORK_DIR="/tmp/piki-obs"
mkdir -p "$WORK_DIR"

INFRA_RAW_BASE="https://raw.githubusercontent.com/TeamPiKi/infra/$REF/blocks/alloy"

# --- 1. 공통 Alloy 블록 fetch (repo public, 인증 불필요) ---
curl -fsSL "$INFRA_RAW_BASE/config.alloy" -o "$WORK_DIR/config.alloy" \
  || { echo "config.alloy fetch 실패 (ref=$REF)" >&2; exit 1; }
curl -fsSL "$INFRA_RAW_BASE/provision-alloy.sh" -o "$WORK_DIR/provision-alloy.sh" \
  || { echo "provision-alloy.sh fetch 실패 (ref=$REF)" >&2; exit 1; }

# --- 2. Grafana Cloud 자격을 SSM Parameter Store 에서 조회 ---
# 자격의 정본은 공유 경로 /piki/observability/grafana-* 하나다(세 서비스 박스가 같은 경로를 읽어
# 토큰 회전이 1곳 put-parameter 로 끝난다 — TeamPiKi/core#771, 계약: TeamPiKi/infra contracts/observability.md).
# 박스엔 aws cli 가 없어 dockerized aws cli 로 조회한다.
get_ssm_param() {
  local param_name="$1"
  docker run --rm --network host \
    -e AWS_DEFAULT_REGION=ap-northeast-2 \
    amazon/aws-cli ssm get-parameter \
    --name "$param_name" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

# metrics/logs/token 5종은 필수 - 없으면 관측이 성립하지 않으므로 즉시 실패.
GRAFANA_METRICS_URL=$(get_ssm_param /piki/observability/grafana-metrics-url) \
  || { echo "필수 SSM 파라미터 조회 실패: /piki/observability/grafana-metrics-url" >&2; exit 1; }
GRAFANA_METRICS_USER=$(get_ssm_param /piki/observability/grafana-metrics-user) \
  || { echo "필수 SSM 파라미터 조회 실패: /piki/observability/grafana-metrics-user" >&2; exit 1; }
GRAFANA_LOGS_URL=$(get_ssm_param /piki/observability/grafana-logs-url) \
  || { echo "필수 SSM 파라미터 조회 실패: /piki/observability/grafana-logs-url" >&2; exit 1; }
GRAFANA_LOGS_USER=$(get_ssm_param /piki/observability/grafana-logs-user) \
  || { echo "필수 SSM 파라미터 조회 실패: /piki/observability/grafana-logs-user" >&2; exit 1; }
GRAFANA_CLOUD_TOKEN=$(get_ssm_param /piki/observability/grafana-cloud-token) \
  || { echo "필수 SSM 파라미터 조회 실패: /piki/observability/grafana-cloud-token" >&2; exit 1; }

# traces 2종은 선택 - 없으면 빈 값으로 둔다. 공통 config.alloy 가 더미 endpoint 로
# fallback 해 trace export 만 비활성화되고(metrics/logs 는 정상 동작), 스크립트는 실패하지 않는다.
GRAFANA_TRACES_URL=$(get_ssm_param /piki/observability/grafana-traces-url) || GRAFANA_TRACES_URL=""
GRAFANA_TRACES_USER=$(get_ssm_param /piki/observability/grafana-traces-user) || GRAFANA_TRACES_USER=""

export GRAFANA_METRICS_URL GRAFANA_METRICS_USER
export GRAFANA_LOGS_URL GRAFANA_LOGS_USER
export GRAFANA_TRACES_URL GRAFANA_TRACES_USER
export GRAFANA_CLOUD_TOKEN

# --- 3. 공통 블록 호출 - 이 박스는 prod 전용이라 environment=prod 고정 ---
# (dev extractor 는 core dev 박스에 동거하며 그쪽 배선은 core 소관, 여기서 다루지 않는다)
bash "$WORK_DIR/provision-alloy.sh" \
  --config "$WORK_DIR/config.alloy" \
  --name piki-alloy \
  --environment prod \
  --box piki-extractor
