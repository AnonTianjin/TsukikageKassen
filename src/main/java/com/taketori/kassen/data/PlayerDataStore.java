package com.taketori.kassen.data;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家数据存储接口。
 *
 * <p>保存"玩家 ↔ 角色"的绑定与"玩家 ↔ 隐性标签"。默认实现是 YAML；
 * 换成 SQL 只需提供另一个实现，调用方不用改。</p>
 */
public interface PlayerDataStore {

    void loadAll();

    String characterIdOf(UUID uuid);

    void setCharacterId(UUID uuid, String characterId);

    /** 隐性标签 id（可能为 null）。 */
    String tagOf(UUID uuid);

    void setTag(UUID uuid, String tag);

    void remove(UUID uuid);

    void saveAll();

    Map<UUID, String> snapshot();

    /** 玩家 → 标签 的快照（用于管理界面展示）。 */
    Map<UUID, String> tagSnapshot();
}
