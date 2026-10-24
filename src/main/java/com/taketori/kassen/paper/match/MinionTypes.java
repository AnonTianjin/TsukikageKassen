package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 月人配置：普通种类（按权重随机）与精英规格（每 N 波刷一批，钻甲 + 药水 buff）。
 *
 * <p>全部来自 <code>config.yml</code> 的 <code>minion</code> 段，改动后 <code>/taketori reload</code> 生效：</p>
 *
 * <pre>
 * minion:
 *   types:            # 普通月人种类与权重
 *     ZOMBIE: 3
 *     SKELETON: 2
 *   elite:
 *     every-waves: 5  # 每 5 波出一批精英
 *     count: 2
 *     types: [ ZOMBIE, SKELETON ]
 *     armor: DIAMOND
 *     attack-damage: 15.0   # 单位是生命点（2 点 = 1 颗心）
 *     buffs: [ { type: SPEED, amplifier: 1, duration-ticks: 1200 } ]
 * </pre>
 */
public final class MinionTypes {

    /** 一种普通月人。 */
    public record Kind(EntityType type, int weight) {
    }

    /** 一条药水效果。 */
    public record Buff(String typeName, int amplifier, int durationTicks) {
    }

    /** 精英规格。 */
    public record Elite(List<EntityType> types,
                        int everyWaves,
                        int count,
                        int maxAlive,
                        double health,
                        String armor,
                        double attackDamage,
                        String display,
                        List<Buff> buffs) {

        public boolean enabled() {
            return everyWaves > 1 && count > 0 && !types.isEmpty();
        }

        public boolean isEliteWave(int wave) {
            return enabled() && wave > 0 && wave % everyWaves == 0;
        }
    }

    private final TaketoriPlugin plugin;
    private List<Kind> kinds = List.of();
    private Elite elite = new Elite(List.of(), 0, 0, 0, 0.0D, "NONE", 0.0D, "精英月人", List.of());

    public MinionTypes(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 读取配置（启动与 reload 时调用）。 */
    public void load() {
        var cfg = plugin.getConfig();

        List<Kind> parsed = new ArrayList<>();
        ConfigurationSection kindSection = cfg.getConfigurationSection("minion.types");
        if (kindSection != null) {
            for (String key : kindSection.getKeys(false)) {
                EntityType type = parseEntity(key);
                int weight = Math.max(1, kindSection.getInt(key, 1));
                if (type == null) {
                    plugin.getLogger().warning("minion.types 里的 " + key + " 不是有效的实体类型，已忽略");
                    continue;
                }
                parsed.add(new Kind(type, weight));
            }
        }
        if (parsed.isEmpty()) {
            // 兼容旧配置：没写 types 就按"僵尸"处理
            parsed.add(new Kind(EntityType.ZOMBIE, 1));
        }
        kinds = List.copyOf(parsed);

        List<EntityType> eliteTypes = new ArrayList<>();
        for (String name : cfg.getStringList("minion.elite.types")) {
            EntityType type = parseEntity(name);
            if (type == null) {
                plugin.getLogger().warning("minion.elite.types 里的 " + name + " 不是有效的实体类型，已忽略");
                continue;
            }
            eliteTypes.add(type);
        }

        List<Buff> buffs = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("minion.elite.buffs")) {
            Object typeName = raw.get("type");
            if (typeName == null) {
                continue;
            }
            int amplifier = raw.get("amplifier") instanceof Number number ? number.intValue() : 0;
            int duration = raw.get("duration-ticks") instanceof Number number ? number.intValue() : 600;
            buffs.add(new Buff(String.valueOf(typeName), Math.max(0, amplifier), Math.max(20, duration)));
        }

        elite = new Elite(
                List.copyOf(eliteTypes),
                Math.max(0, cfg.getInt("minion.elite.every-waves", 5)),
                Math.max(0, cfg.getInt("minion.elite.count", 2)),
                Math.max(0, cfg.getInt("minion.elite.max-alive", 4)),
                Math.max(1.0D, cfg.getDouble("minion.elite.health", 80.0D)),
                cfg.getString("minion.elite.armor", "DIAMOND"),
                Math.max(0.0D, cfg.getDouble("minion.elite.attack-damage", 15.0D)),
                cfg.getString("minion.elite.name", "<dark_red>精英月人</dark_red>"),
                List.copyOf(buffs));

        if (plugin.config().debug()) {
            plugin.getLogger().info("[match] 月人种类 " + kinds.size() + " 种，精英每 "
                    + elite.everyWaves() + " 波刷 " + elite.count() + " 只（" + elite.types().size() + " 种实体）");
        }
    }

    private EntityType parseEntity(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public List<Kind> kinds() {
        return kinds;
    }

    public Elite elite() {
        return elite;
    }

    /** 按权重随机抽一种普通月人。 */
    public Kind randomKind() {
        if (kinds.isEmpty()) {
            return new Kind(EntityType.ZOMBIE, 1);
        }
        int total = 0;
        for (Kind kind : kinds) {
            total += kind.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (Kind kind : kinds) {
            roll -= kind.weight();
            if (roll < 0) {
                return kind;
            }
        }
        return kinds.get(kinds.size() - 1);
    }

    /** 随机抽一种精英实体。 */
    public EntityType randomEliteType() {
        List<EntityType> types = elite.types();
        if (types.isEmpty()) {
            return EntityType.ZOMBIE;
        }
        return types.get(ThreadLocalRandom.current().nextInt(types.size()));
    }
}
