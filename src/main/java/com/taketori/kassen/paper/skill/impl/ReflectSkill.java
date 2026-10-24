package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.entity.Player;

/**
 * 反射窗口：在一段时间内把来袭的<b>飞行物弹回发射者</b>。
 *
 * <p>这是月镜"镜面模式"的核心机制，也是全插件唯一能反制远程的手段：
 * 箭、雪球、火球打到你时会以原速度（可乘倍率）反向飞回去，且伤害归属改为你。
 * 对近战无效——所以镜面模式怕贴脸，这是设计上的取舍。</p>
 *
 * <p>实际反弹在 {@code CombatListener} 里完成，这里只负责开窗口与表现。</p>
 */
public final class ReflectSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ReflectSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "reflect";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        int duration = context.integer("duration-ticks", 100);
        double speedMultiplier = context.dbl("speed-multiplier", 1.0D);
        double absorption = context.dbl("absorption", 0.0D);
        // 2.0：近战反制比例——窗口内被贴脸打中时按比例反弹，否则镜面一被近身就失效
        double reflectRatio = context.dbl("reflect-ratio", 0.0D);

        plugin.states().setReflection(player.getUniqueId(), duration, speedMultiplier, reflectRatio,
                context.weapon().id());
        if (absorption > 0.0D) {
            SkillTargets.addAbsorption(player, absorption);
        }

        plugin.fx().actionBar(player, plugin.config().messages().get("skill.mirror-ready",
                "time", SkillManager.formatSeconds(duration / 20.0D)));
        plugin.fx().sound(context.str("sound", "BLOCK_GLASS_BREAK"), player, 0.8F, 1.8F);
        plugin.fx().particle(context.str("particle", "END_ROD"),
                player.getLocation().add(0.0D, 1.0D, 0.0D), 28, 0.5D);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[combat] reflect 窗口开启 " + (duration / 20.0D) + "s（速度倍率 " + speedMultiplier + "）");
        }
        return SkillResult.SUCCESS;
    }
}
