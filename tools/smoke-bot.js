// 最小假人冒烟：连上服务器，执行 /taketori doctor，断言插件有响应。
// 依赖：npm i mineflayer（由 tools/smoke-test.sh 安装）。
const mineflayer = require('mineflayer')

const host = process.env.BOT_HOST || '127.0.0.1'
const port = Number(process.env.BOT_PORT || 25577)
const version = process.env.MC_VERSION || '26.1.2'
const timeoutMs = Number(process.env.BOT_TIMEOUT_MS || 90000)

// 这三条同时出现，说明插件加载完成且指令链路通
const required = [
  /月影合战 自检/,
  /版本 \S+ \/ 适配层/,
  /已加载 \d+ 把武器/
]

const seen = []
let spawned = false
let finished = false

const bot = mineflayer.createBot({
  host,
  port,
  username: process.env.BOT_NAME || 'SmokeBot',
  version,
  auth: 'offline'
})

function fail(reason) {
  if (finished) return
  finished = true
  console.error(`SMOKE FAIL: ${reason}`)
  console.error('--- 最近收到的消息 ---')
  console.error(seen.slice(-30).join('\n'))
  try { bot.quit() } catch (ignored) { /* 已断开 */ }
  process.exit(1)
}

const timer = setTimeout(() => fail(`${timeoutMs}ms 内没有等到预期输出`), timeoutMs)

bot.on('login', () => console.log('SMOKE: login'))
bot.on('spawn', () => {
  spawned = true
  console.log('SMOKE: spawn')
  bot.chat('/taketori doctor')
})
bot.on('kicked', (reason) => fail(`被踢出：${typeof reason === 'string' ? reason : JSON.stringify(reason)}`))
bot.on('error', (error) => fail(`连接错误：${error.message}`))
bot.on('end', () => {
  if (!finished) fail('连接提前结束')
})
bot.on('message', (message) => {
  const text = message.toString()
  seen.push(text)
  if (spawned && required.every((pattern) => seen.some((line) => pattern.test(line)))) {
    finished = true
    clearTimeout(timer)
    console.log('SMOKE PASS: 插件已加载且 /taketori doctor 有响应')
    bot.quit()
    process.exit(0)
  }
})
