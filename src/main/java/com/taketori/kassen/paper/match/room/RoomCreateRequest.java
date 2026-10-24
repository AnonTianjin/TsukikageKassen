package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.core.match.sengoku.SengokuMode;

/**
 * 创建房间的全部入参，集中在一条请求里往下传。
 *
 * <p>之前 {@link RoomManager#createRoom} 只收一个模板 id（null = 默认模板），房间模式
 * 则一律从全局 {@code match.mode} 快照。现在允许创建者同时指定<b>地图模板</b>与<b>游戏模式</b>，
 * 两者都放进本请求，避免每种新选项都去改一遍方法签名、并在调用链上层层追加参数。</p>
 *
 * <p><b>扩展点</b>：将来要给创建房间加观战开关、房间密码、私有房标记等，只需在本记录上
 * 加字段（并让 GUI / 命令层填进去），创建流程与 {@link RoomManager} 主体无需再动。</p>
 *
 * @param templateId 地图模板 id（arenas.yml 条目名）；null/空白表示用默认模板。
 * @param mode       房间初始模式；null 表示沿用全局 {@code match.mode} 配置。
 */
public record RoomCreateRequest(String templateId, SengokuMode mode) {

    /** 只用默认模板 + 沿用全局模式的简写。 */
    public static RoomCreateRequest defaults() {
        return new RoomCreateRequest(null, null);
    }

    /** 指定模板、沿用全局模式。 */
    public static RoomCreateRequest withTemplate(String templateId) {
        return new RoomCreateRequest(templateId == null || templateId.isBlank() ? null : templateId, null);
    }

    /** 指定模板与模式。 */
    public static RoomCreateRequest of(String templateId, SengokuMode mode) {
        return new RoomCreateRequest(templateId == null || templateId.isBlank() ? null : templateId, mode);
    }
}
