package com.taketori.kassen.data;

import java.util.Map;
import java.util.UUID;

/**
 * 统一数据持久化接口：玩家档案（角色绑定 / 隐性标签）与跨局战绩共用一套后端。
 *
 * <p>继承 {@link PlayerDataStore}，所以既有调用点（{@code plugin.dataStore().characterIdOf(...)}、
 * {@code setTag(...)}、{@code saveAll()}）一行都不用改；这里只额外补上战绩与生命周期。</p>
 *
 * <p><b>两种写入模型</b>：</p>
 * <ul>
 *   <li>玩家档案：{@code setCharacterId / setTag / remove} 即时写穿（SQL）或标脏（YAML），
 *       内存里始终有一份完整快照供查询；</li>
 *   <li>战绩：内存里由 {@link #loadStats()} 载入、由调用方聚合，结算时整批
 *       {@link #saveStats(Map)} 落盘；SQL 后端是一条条 UPSERT，不删旧行。</li>
 * </ul>
 *
 * <p>战绩以 <b>UUID</b> 为键：昵称可以改、可以重名，UUID 不会。老版本按玩家名存的
 * {@code stats.yml} 行会在读取时尝试按 usercache 映射到 UUID，映射不到的仍原样保留。</p>
 *
 * <p>实现必须线程安全：数据可能在主线程（绑定角色）与异步任务（定期落盘）里同时被访问。</p>
 */
public interface DataStore extends PlayerDataStore {

    /** 后端标识：{@code yaml} / {@code sqlite} / {@code mysql}，写进启动日志与自检输出。 */
    String backend();

    /**
     * 载入全部战绩：玩家 UUID → 统计项键名 → 数值。
     *
     * <p>键名沿用 {@code stats.yml} 的字段名（{@code total-score} / {@code kills} …），
     * 这样 YAML 与 SQL 之间可以互换而不丢数据。</p>
     */
    Map<UUID, Map<String, Long>> loadStats();

    /** 整批写入战绩（只写非 0 项；已存在的行覆盖为传入值）。 */
    void saveStats(Map<UUID, Map<String, Long>> stats);

    /**
     * 删除某个玩家的全部战绩行，返回删除的统计项条数。
     *
     * <p>只给 UUID 迁移用：数值并到新键之后旧行必须清掉，否则同一条命令再执行一次就会把成绩翻倍。</p>
     */
    int removeStats(UUID uuid);

    /** 断开连接 / 落盘。插件停用时调用，可重复调用。 */
    void close();
}
