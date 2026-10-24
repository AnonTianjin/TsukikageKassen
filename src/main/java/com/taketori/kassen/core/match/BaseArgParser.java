package com.taketori.kassen.core.match;

/**
 * 划定基地 / 出生点指令的参数解析：把玩家的各种写法统一成「队伍 + 编号」。
 *
 * <p>为什么要有它：划基地最常见的失败原因就是参数写法不对，而旧实现只回一句
 * "队伍或编号无效"，让人无从下手。支持的写法（都是实机真会遇到的）：</p>
 *
 * <ul>
 *   <li>{@code red 1} / {@code blue 3} —— 标准写法；</li>
 *   <li>{@code 1 red} —— 顺序写反；</li>
 *   <li>{@code red} —— 省略编号（index = {@link #NO_INDEX}，由调用方自动分配空位）；</li>
 *   <li>{@code 红 2} / {@code 蓝} —— 中文队伍名；</li>
 *   <li>{@code red ２} —— 中文输入法的全角数字（自动归一化）。</li>
 * </ul>
 *
 * <p>纯逻辑、零 Bukkit 依赖，因此能用 {@code tools/BaseArgParserTest.java} 离线回归。</p>
 */
public final class BaseArgParser {

    /** 编号未提供或无法解析时的取值。 */
    public static final int NO_INDEX = -1;

    private BaseArgParser() {
    }

    /**
     * 解析两个参数。
     *
     * @param first  第一个参数（一般是队伍名，也可能是编号）
     * @param second 第二个参数，可为 {@code null}
     * @return 解析结果；队伍无效时 {@code team()} 为 {@code null}，编号无效或缺失时 {@code index()} 不为正
     */
    public static Result parse(String first, String second) {
        TeamId team = TeamId.byName(first);
        int index = parseIndex(second);
        if (team == null && second != null) {
            TeamId swapped = TeamId.byName(second);
            if (swapped != null) {
                team = swapped;
                index = parseIndex(first);
            }
        }
        return new Result(team, index);
    }

    /** 解析编号；全角数字与夹杂的空白会被归一化，非数字返回 {@link #NO_INDEX}。 */
    public static int parseIndex(String text) {
        if (text == null) {
            return NO_INDEX;
        }
        StringBuilder normalized = new StringBuilder();
        for (char c : text.trim().toCharArray()) {
            if (c >= '０' && c <= '９') {
                normalized.append((char) (c - '０' + '0'));
            } else if (!Character.isWhitespace(c)) {
                normalized.append(c);
            }
        }
        if (normalized.isEmpty()) {
            return NO_INDEX;
        }
        try {
            return Integer.parseInt(normalized.toString());
        } catch (NumberFormatException ex) {
            return NO_INDEX;
        }
    }

    /**
     * 解析结果。
     *
     * @param team  队伍；{@code null} 表示队伍参数无效
     * @param index 基地编号；{@code <= 0} 表示没给出有效编号
     */
    public record Result(TeamId team, int index) {

        public boolean hasTeam() {
            return team != null;
        }

        public boolean hasIndex() {
            return index > 0;
        }
    }
}
