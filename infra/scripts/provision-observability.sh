#!/usr/bin/env bash
#
# extractor prod 박스 관측(Alloy) 배선 - 이 스크립트가 값의 SSOT다.
#
# TeamPiKi/infra 의 공통 Alloy 블록을 fetch 하고, extractor 의 값(environment=prod,
# box=piki-extractor)으로 호출한다. 이 스크립트가 갖는 것은 그 **값**뿐이다.
#
# 자격(SSM) 로드는 공용 블록 provision-alloy-ssm.sh 소관이다 - 예전에는 이 스크립트가 직접
# 조회했으나 같은 로직이 core·renderer 에도 복제돼 있어, 경로나 실패 정책이 바뀌면 세 곳을 함께
# 고쳐야 하고 하나만 빠뜨리면 그 박스만 조용히 어긋났다(TeamPiKi/infra#50 에서 블록으로 추출).
#
# 실행 위치: extractor 박스 안. 배포(deploy.yml)가 매 배포 실행하고, 수동 실행(SSM run-command·
# 세션 접속)도 그대로 가능하다.
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
# provision-alloy-ssm.sh 는 자기 형제 경로의 provision-alloy.sh 를 호출하므로 셋을 같은 디렉터리에 받는다.
for f in config.alloy provision-alloy.sh provision-alloy-ssm.sh; do
  curl -fsSL "$INFRA_RAW_BASE/$f" -o "$WORK_DIR/$f" \
    || { echo "$f fetch 실패 (ref=$REF)" >&2; exit 1; }
done

# --- 2. 공통 블록 호출 - 이 박스는 prod 전용이라 environment=prod 고정 ---
# (dev extractor 는 core dev 박스에 동거하며 그쪽 배선은 core 소관, 여기서 다루지 않는다)
# 자격(SSM) 조회·필수/선택 판정은 블록이 한다 - 이 스크립트는 박스 값만 넘긴다.
bash "$WORK_DIR/provision-alloy-ssm.sh" \
  --config "$WORK_DIR/config.alloy" \
  --name piki-alloy \
  --environment prod \
  --box piki-extractor
