#!/usr/bin/env bash
# 起一个真实 Paper 服务器，用假人逐把武器验证技能（右键 / 双击潜行 / 被动 / 忠诚）。
#
# 依赖：java 25、node >= 18、curl。首次运行会下载 Paper（约 50MB）并 npm i mineflayer。
# 复用缓存：若 /tmp/kassen-smoke/paper.jar 已存在则直接拷贝，不再重复下载。
#
# 用法：
#   ./gradlew build
#   bash tools/skill-smoke.sh
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK_DIR:-/tmp/kassen-skill}
PORT=${SERVER_PORT:-25578}
PAPER_BUILD=74
PAPER_SHA=1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA}/paper-26.1.2-${PAPER_BUILD}.jar"
BOT_NAME=${BOT_NAME:-SkillBot}
# 离线模式假人的 UUID（OfflinePlayer:<name> 的 MD5 v3），预置成 OP 才能用 /taketori give
BOT_UUID=dd4098c5-b7b4-3d1d-99f7-ef9006e9265b

SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

log_tail() {
  echo "----- 服务器日志尾部 -----"
  tail -n 80 "$WORK/console.log" 2>/dev/null || echo "(没有日志)"
}

echo "==> 准备目录 $WORK"
mkdir -p "$WORK/plugins"
cd "$WORK"

if [ ! -f paper.jar ]; then
  if [ -f /tmp/kassen-smoke/paper.jar ]; then
    echo "==> 复用已下载的 paper.jar"
    cp /tmp/kassen-smoke/paper.jar .
  else
    echo "==> 下载 Paper 26.1.2 build ${PAPER_BUILD}"
    curl -fsSL -o paper.jar "$PAPER_URL"
    echo "${PAPER_SHA}  paper.jar" | sha256sum -c -
  fi
fi

printf 'eula=true\n' > eula.txt
cat > server.properties <<EOF
server-port=${PORT}
online-mode=false
enforce-secure-profile=false
level-name=world
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
max-players=8
view-distance=4
simulation-distance=4
allow-nether=false
allow-flight=true
gamemode=survival
difficulty=normal
spawn-monsters=false
motd=kassen skill smoke
enable-rcon=false
sync-chunk-writes=false
EOF

cat > ops.json <<EOF
[
  {
    "uuid": "${BOT_UUID}",
    "name": "${BOT_NAME}",
    "level": 4,
    "bypassesPlayerLimit": true
  }
]
EOF

JAR=$(ls "$REPO_ROOT"/build/libs/TsukikageKassen-*.jar 2>/dev/null | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "找不到 build/libs/TsukikageKassen-*.jar，先执行 ./gradlew build"
  exit 1
fi
# 每次都用最新 jar 覆盖，避免测到旧版本
rm -f plugins/TsukikageKassen-*.jar
cp "$JAR" plugins/
# 干净配置：确保测得是当前仓库里的 weapons.yml
rm -rf plugins/TsukikageKassen

echo "==> 启动服务器（端口 ${PORT}）"
java -Xms1G -Xmx1G -jar paper.jar --nogui < /dev/null > console.log 2>&1 &
SERVER_PID=$!

ready=0
for _ in $(seq 1 120); do
  if grep -q 'Done (' console.log 2>/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "服务器提前退出"
    log_tail
    exit 1
  fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then
  echo "240 秒内没等到启动完成"
  log_tail
  exit 1
fi

echo "==> 校验插件加载"
grep -q 'TsukikageKassen v' console.log || { echo "插件未加载"; log_tail; exit 1; }
grep -q '配置校验通过' console.log || { echo "配置校验未通过"; log_tail; exit 1; }

echo "==> 准备假人依赖"
NODE_MODULES=${NODE_MODULES:-/tmp/kassen-smoke/node_modules}
if [ ! -d "$NODE_MODULES/mineflayer" ]; then
  npm init -y >/dev/null 2>&1
  npm i mineflayer --silent --no-audit --no-fund
  NODE_MODULES="$WORK/node_modules"
fi

echo "==> 假人开始逐把武器验证"
NODE_PATH="$NODE_MODULES" BOT_HOST=127.0.0.1 BOT_PORT="$PORT" BOT_NAME="$BOT_NAME" \
  node "$REPO_ROOT/tools/skill-smoke.js" || {
  log_tail
  exit 1
}

echo "==> 技能回归通过"
