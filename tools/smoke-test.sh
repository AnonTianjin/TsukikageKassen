#!/usr/bin/env bash
# 起一个真实的 Paper 26.1.2 服务器，用假人验证插件能加载、指令有响应。
#
# 本地实测环境（2026-11-01）：Debian 13 + OpenJDK 25.0.5 + Node 24 + Paper 26.1.2 build 74。
# 依赖：java 25、node >= 18、curl、sha256sum（镜像见 image/Dockerfile）。
#
# 用法：
#   ./gradlew build            # 先产出 build/libs/TsukikageKassen-*.jar
#   bash tools/smoke-test.sh
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK_DIR:-/tmp/kassen-smoke}
PORT=${SERVER_PORT:-25577}
PAPER_BUILD=74
# 固定校验值：换 build 时两处一起改（sha 同时用在下载地址里）
PAPER_SHA=1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7
PAPER_URL="https://fill-data.papermc.io/v1/objects/${PAPER_SHA}/paper-26.1.2-${PAPER_BUILD}.jar"

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
  tail -n 60 "$WORK/console.log" 2>/dev/null || echo "(没有日志)"
}

echo "==> 检查环境"
java -version 2>&1 | head -n 1
node -v
command -v npm >/dev/null || { echo "缺少 npm"; exit 1; }

echo "==> 准备目录 $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/plugins"
cd "$WORK"

echo "==> 下载 Paper 26.1.2 build ${PAPER_BUILD}"
curl -fsSL -o paper.jar "$PAPER_URL"
echo "${PAPER_SHA}  paper.jar" | sha256sum -c -

printf 'eula=true\n' > eula.txt
cat > server.properties <<'EOF'
server-port=25577
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
motd=kassen smoke
enable-rcon=false
sync-chunk-writes=false
EOF
# 端口以变量为准，覆盖模板里的默认值
sed -i "s/^server-port=.*/server-port=${PORT}/" server.properties

JAR=$(ls "$REPO_ROOT"/build/libs/TsukikageKassen-*.jar 2>/dev/null | head -n 1 || true)
if [ -z "$JAR" ]; then
  echo "找不到 build/libs/TsukikageKassen-*.jar，先执行 ./gradlew build"
  exit 1
fi
echo "==> 装入插件 $(basename "$JAR")"
cp "$JAR" "$WORK/plugins/"

echo "==> 启动服务器（端口 ${PORT}）"
java -Xms1G -Xmx1G -jar paper.jar --nogui > console.log 2>&1 &
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

echo "==> 校验插件加载日志"
grep -q 'TsukikageKassen v' console.log || { echo "插件未加载"; log_tail; exit 1; }
grep -q '配置校验通过' console.log || { echo "配置校验未通过"; log_tail; exit 1; }
grep -E 'TsukikageKassen v.*已启用' console.log | tail -n 1

echo "==> 安装假人依赖（mineflayer）"
cd "$WORK"
if [ ! -f package.json ]; then
  npm init -y >/dev/null 2>&1
fi
npm i mineflayer --silent --no-audit --no-fund

echo "==> 假人连接并执行 /taketori doctor"
# NODE_PATH 指到 $WORK：脚本本体在仓库 tools/ 下，Node 只会从脚本所在目录往上找
# node_modules，不设这一项会报 Cannot find module 'mineflayer'
NODE_PATH="$WORK/node_modules" BOT_HOST=127.0.0.1 BOT_PORT="$PORT" \
  node "$REPO_ROOT/tools/smoke-bot.js" || {
  log_tail
  exit 1
}

echo "==> 冒烟通过"
