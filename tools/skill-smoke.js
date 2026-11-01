// 假人全武器技能回归：逐把武器检查 右键 / 双击潜行 / 被动 是否真的生效。
//
// 判定依据（全部来自服务端回显，不猜客户端状态）：
//   1. /taketori debug 的 `right=类型(cd ...)` / `sneak=类型(cd ...)` → 技能真的派发进了冷却
//   2. 木桩僵尸（NoAI）的血量变化 → 右击技能 / 左键普攻确实造成了伤害
//   3. `data get ... active_effects` → 减速类被动真的挂上了
//   4. 三叉戟投掷后物品回到背包 → 忠诚生效
//
// 依赖 mineflayer（由 tools/skill-smoke.sh 准备）。用法见该脚本。
const mineflayer = require('mineflayer')

const host = process.env.BOT_HOST || '127.0.0.1'
const port = Number(process.env.BOT_PORT || 25578)
const version = process.env.MC_VERSION || '26.1.2'
const botName = process.env.BOT_NAME || 'SkillBot'
const timeoutMs = Number(process.env.BOT_TIMEOUT_MS || 600000)

const WEAPONS = [
    'tsukuyomi_hammer', 'tsukuyomi_bell', 'shogun_konbo', 'shogun_odachi',
    'hayate_sword', 'hayate_wire', 'hayate_kunai',
    'yuzuki_bow', 'yuzuki_dagger',
    'tatewaki_shield', 'tatewaki_banner',
    'moon_mirror', 'frozen_swordfish',
    'kurogane_fang', 'kurogane_collar', 'kurogane_iron_fang',
    'kage_gun', 'kage_lens',
    'yoshino_reed', 'yoshino_thread'
]

// 右键留空的武器：右键应走原版（拉弓 / 投掷），不应出现 right 冷却
const VANILLA_RIGHT = new Set(['yuzuki_bow', 'frozen_swordfish'])

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let buffer = []
const results = []
let finished = false

const bot = mineflayer.createBot({ host, port, username: botName, version, auth: 'offline' })

const timer = setTimeout(() => fail(`总超时：${timeoutMs}ms 内没跑完`), timeoutMs)

bot.on('message', (message) => {
    buffer.push(message.toString())
})

bot.on('kicked', (reason) => fail(`被踢出：${JSON.stringify(reason).slice(0, 300)}`))
bot.on('error', (error) => fail(`连接错误：${error.message}`))
bot.on('end', () => {
    if (!finished) fail('连接提前结束')
})

bot.once('spawn', () => {
    run().catch((error) => fail(error.stack || String(error)))
})

function fail(reason) {
    if (finished) return
    finished = true
    clearTimeout(timer)
    console.error(`SKILL SMOKE FAIL: ${reason}`)
    report(false)
    try { bot.quit() } catch (ignored) { /* 已断开 */ }
    process.exit(1)
}

async function run() {
    await sleep(2000)
    const home = bot.entity.position.clone()

    await cmd('/gamemode survival ' + botName)
    await cmd('/difficulty normal')
    await cmd('/gamerule doMobSpawning false')
    await cmd('/gamerule doDaylightCycle false')
    await cmd('/gamerule keepInventory true')
    await cmd('/time set noon')
    await cmd('/weather clear')
    await cmd('/effect clear ' + botName)
    // /taketori debug 只在 debug 开启时输出状态摘要，回归靠它读冷却与被动类型
    await cmd('/taketori debug on', 600)

    for (const weapon of WEAPONS) {
        await testWeapon(weapon, home)
    }

    finished = true
    clearTimeout(timer)
    const failed = results.filter((r) => !r.ok)
    report(true)
    console.log(`skill-smoke: ${results.length - failed.length}/${results.length} 项通过`)
    bot.quit()
    process.exit(failed.length === 0 ? 0 : 1)
}

async function testWeapon(weapon, home) {
    // 回到固定点：火箭跳 / 闪现会把假人挪走，不重置会让后续测试打不到木桩
    await cmd(`/tp ${botName} ${home.x.toFixed(2)} ${home.y.toFixed(2)} ${home.z.toFixed(2)}`)
    await cmd('/clear ' + botName)
    await cmd('/kill @e[type=zombie]')
    await cmd(`/taketori give ${botName} ${weapon}`, 400)

    const item = bot.inventory.items().find((entry) => entry.customName || entry.nbt)
        || bot.inventory.items()[0]
    if (!item) {
        record(weapon, 'give', false, '没有拿到武器物品')
        return
    }
    await bot.equip(item, 'hand')
    await sleep(300)

    // 木桩：NoAI 僵尸，血量拉高（否则两下就打死了，被动统计不到），站在正前方 2.5 格
    await cmd(`/execute at @s run summon zombie ^ ^ ^2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["dummy"],Attributes:[{Name:"minecraft:max_health",Base:1000.0}]}`, 400)
    await cmd('/attribute @e[tag=dummy,limit=1,sort=nearest] minecraft:max_health base set 1000', 250)
    await cmd('/data merge entity @e[tag=dummy,limit=1,sort=nearest] {Health:1000.0f}', 250)
    const dummy = nearestDummy()
    if (!dummy) {
        record(weapon, 'dummy', false, '木桩没有刷出来')
        return
    }
    await bot.lookAt(dummy.position.offset(0, 1.2, 0), true)
    await sleep(200)

    // ── 右键 ────────────────────────────────────────────────
    await cmd('/taketori debug', 200)   // 先清掉上一次的输出
    buffer = []
    bot.activateItem()
    await sleep(500)
    const afterRight = await cmd('/taketori debug', 500)
    const rightCooldown = /right=\S+\(cd /.test(afterRight)
    if (VANILLA_RIGHT.has(weapon)) {
        record(weapon, 'right', !rightCooldown, rightCooldown ? '右键被技能吃掉（应为原版）' : '右键保持原版')
    } else {
        record(weapon, 'right', rightCooldown, rightCooldown ? '技能已进入冷却' : '右键没有触发技能')
    }

    // ── 双击潜行 ────────────────────────────────────────────
    buffer = []
    bot.setControlState('sneak', true)
    await sleep(90)
    bot.setControlState('sneak', false)
    await sleep(90)
    bot.setControlState('sneak', true)
    await sleep(90)
    bot.setControlState('sneak', false)
    await sleep(500)
    const afterSneak = await cmd('/taketori debug', 500)
    const sneakCooldown = /sneak=\S+\(cd /.test(afterSneak)
    record(weapon, 'sneak', sneakCooldown, sneakCooldown ? '移动技能已进入冷却' : '双击潜行没有触发技能')

    // ── 被动（左键普攻命中木桩）──────────────────────────────
    const passiveLine = /passive=([A-Z_]+)/.exec(afterSneak)
    const passive = passiveLine ? passiveLine[1] : 'NONE'
    const healthBefore = await entityHealth()
    let hits = 0
    let slowSeen = false
    for (let i = 0; i < 12; i++) {
        let target = nearestDummy()
        if (!target) {
            // 木桩被打死（低伤武器也可能拖到 10 下）→ 补一只，测试继续
            await cmd(`/execute at @s run summon zombie ^ ^ ^2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["dummy"],Attributes:[{Name:"minecraft:max_health",Base:1000.0}]}`, 350)
            await cmd('/attribute @e[tag=dummy,limit=1,sort=nearest] minecraft:max_health base set 1000', 200)
            await cmd('/data merge entity @e[tag=dummy,limit=1,sort=nearest] {Health:1000.0f}', 200)
            target = nearestDummy()
            if (!target) break
        }
        await bot.lookAt(target.position.offset(0, 1.2, 0), true)
        bot.attack(target)
        hits++
        await sleep(420)
        // 减速只持续 2~3 秒，必须每一下都看，等到循环结束再查大概率已经过期
        if (passive === 'SLOW') {
            const effects = await cmd('/data get entity @e[tag=dummy,limit=1,sort=nearest] active_effects', 220)
            if (/slowness/i.test(effects)) {
                slowSeen = true
            }
        }
    }
    await sleep(400)
    const healthAfter = await entityHealth()
    const damaged = healthBefore !== null && healthAfter !== null && healthAfter < healthBefore

    if (passive === 'NONE') {
        record(weapon, 'passive', true, '该武器没有命中类被动')
    } else if (passive === 'SLOW') {
        record(weapon, 'passive', slowSeen,
            slowSeen ? `木桩已挂减速（命中 ${hits} 次）`
                : `减速未出现（命中 ${hits} 次，血量 ${healthBefore} → ${healthAfter}）`)
    } else {
        record(weapon, 'passive', damaged,
            damaged ? `${passive}：普攻命中 ${hits} 次，血量 ${healthBefore} → ${healthAfter}`
                : `${passive}：木桩没掉血（命中 ${hits} 次）`)
    }

    // ── 三叉戟：忠诚回手 ────────────────────────────────────
    if (weapon === 'frozen_swordfish') {
        await cmd('/clear ' + botName)
        await cmd(`/taketori give ${botName} frozen_swordfish`, 400)
        const trident = bot.inventory.items()[0]
        await bot.equip(trident, 'hand')
        await sleep(300)
        await bot.lookAt(dummy ? dummy.position.offset(0, 1.2, 0) : bot.entity.position.offset(0, 1, 3), true)
        bot.activateItem()          // 投掷
        await sleep(3500)
        const back = bot.inventory.items().some((entry) =>
            /trident/i.test(entry.name || '') || entry.customName)
        record(weapon, 'loyalty', back, back ? '投掷后已回到背包' : '投掷后没有回手')
    }
}

function nearestDummy() {
    const id = Object.keys(bot.entities).find((key) => {
        const entity = bot.entities[key]
        return entity && entity.name === 'zombie'
    })
    return id ? bot.entities[id] : null
}

async function entityHealth() {
    const text = await cmd('/data get entity @e[tag=dummy,limit=1,sort=nearest] Health', 400)
    const match = /(-?\d+(?:\.\d+)?)[fFdD]?\s*$/.exec(text.trim().split('\n').pop() || '')
    if (!match) return null
    return Number(match[1])
}

async function cmd(text, wait = 300) {
    buffer = []
    bot.chat(text)
    await sleep(wait)
    return buffer.join('\n')
}

function record(weapon, slot, ok, detail) {
    results.push({ weapon, slot, ok, detail })
    console.log(`${ok ? '[OK]  ' : '[FAIL]'} ${weapon.padEnd(20)} ${slot.padEnd(8)} ${detail}`)
}

function report(final) {
    if (results.length === 0) return
    console.log('\n===== 汇总 =====')
    for (const result of results) {
        if (result.ok) continue
        console.log(`[FAIL] ${result.weapon} / ${result.slot}：${result.detail}`)
    }
    if (final) {
        const failed = results.filter((r) => !r.ok).length
        console.log(`共 ${results.length} 项，失败 ${failed} 项`)
    }
}
