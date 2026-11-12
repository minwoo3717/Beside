#!/usr/bin/env bash
# 팀 Basic 인증 비밀번호를 정하거나 바꾼다(VM 에서). 두 번 묻고(화면에 안 보임) bcrypt 해시만 deploy/.env 의
# BASIC_AUTH_HASH 에 작은따옴표로 넣는다(README '해시의 $ 처리'). caddy 가 떠 있으면 새 값으로 다시 만든다.
# 비밀번호는 caddy hash-password 의 표준 입력으로만 건네서 프로세스 목록·파일·셸 기록에 남지 않는다.
#   개발 PC 에서:  ssh -t beside-gcp 'bash ~/Beside/deploy/set_basic_auth.sh'
#   VM 에서:      bash ~/Beside/deploy/set_basic_auth.sh
# BESIDE_ENV_FILE 로 다른 .env 를 고를 수 있다(시험용).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${BESIDE_ENV_FILE:-$here/.env}"
C=(docker compose -f "$here/docker-compose.yml" --env-file "$ENV_FILE")
# compose 와 같은 caddy 이미지로 해시를 만든다(버전을 두 곳에 적지 않게).
CADDY_IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(caddy:[^[:space:]]*\).*/\1/p' "$here/docker-compose.yml" | head -n1)"

[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 가 없습니다(deploy/README '리포와 .env')." >&2; exit 2; }
[ -n "$CADDY_IMAGE" ] || { echo "docker-compose.yml 에서 caddy 이미지를 찾지 못했습니다." >&2; exit 2; }
[ -t 0 ] || { echo "비밀번호를 입력할 터미널이 없습니다: ssh -t 로 실행하세요." >&2; exit 2; }

read -rsp "새 팀 비밀번호 (10자 이상): " p1; echo
read -rsp "한 번 더: " p2; echo
if [ "$p1" != "$p2" ]; then unset p1 p2; echo "두 번 입력한 값이 다릅니다. 다시 실행하세요." >&2; exit 1; fi
unset p2
if [ "${#p1}" -lt 10 ]; then unset p1; echo "10자 이상으로 정해 주세요." >&2; exit 1; fi
case "$p1" in *[[:space:]]*) unset p1; echo "공백 없이 정해 주세요." >&2; exit 1 ;; esac

h="$(printf '%s\n' "$p1" | docker run --rm -i "$CADDY_IMAGE" caddy hash-password | tr -d '\r\n')"
unset p1
if [[ ! "$h" =~ ^\$2a\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]]; then echo "해시를 만들지 못했습니다. 다시 실행하세요." >&2; exit 1; fi

if grep -q '^BASIC_AUTH_HASH=' "$ENV_FILE"; then
  sed -i "s|^BASIC_AUTH_HASH=.*|BASIC_AUTH_HASH='${h}'|" "$ENV_FILE"
else
  printf "BASIC_AUTH_HASH='%s'\n" "$h" >> "$ENV_FILE"
fi
grep -qx "BASIC_AUTH_HASH='${h}'" "$ENV_FILE" || { echo "$ENV_FILE 에 쓰지 못했습니다." >&2; exit 1; }
echo "저장했습니다: .env 에는 해시만 들어갔습니다 (사용자 이름: $(sed -n 's/^BASIC_AUTH_USER=//p' "$ENV_FILE" | tail -n1))."

# docker-compose.yml 은 프로젝트 이름을 beside 로 고정한다. 그래서 다른 폴더에 복사한 compose 파일로 명령해도
# 지금 떠 있는 서버를 건드린다(2026-11-13 시험에서 실제 caddy·backend 를 시험용 설정으로 다시 만들었다).
# 떠 있는 caddy 가 바로 이 체크아웃의 compose 파일과 .env 로 만든 것일 때만, caddy 하나만 다시 만든다.
cid="$("${C[@]}" ps -q caddy 2>/dev/null || true)"
if [ -n "$cid" ]; then
  made_from="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$cid")"
  if [ "$made_from" = "$here/docker-compose.yml" ] && [ "$ENV_FILE" = "$here/.env" ]; then
    "${C[@]}" up -d --no-deps caddy >/dev/null 2>&1 && echo "caddy 를 새 비밀번호로 다시 띄웠습니다."
  else
    echo "떠 있는 caddy 는 다른 체크아웃($made_from)의 것이라 건드리지 않았습니다. 그 폴더에서 up -d --no-deps caddy 하세요."
  fi
fi
echo "비밀번호는 팀에 따로 전달하세요(리포·채팅 기록에 남기지 않기)."
