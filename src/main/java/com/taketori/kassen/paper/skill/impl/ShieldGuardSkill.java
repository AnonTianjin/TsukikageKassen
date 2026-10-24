package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.paper.skill.SkillTargets;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 强化防御（盾胁）：给玩家一个时间窗，窗内减伤、吸收伤害，并按比例反弹近战伤害。
 *
 * <p>不新增按键，也不与举盾的原版行为冲突——举盾照旧由原版处理，
 * 这个技能只叠加一个增益窗口。</p>
 */
public final class ShieldGuardSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ShieldGuardSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "shield_guard";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        int duration = context.integer("duration-ticks", 100);
        double reflection = context.dbl("reflect-ratio", 0.3D);
        double absorption = context.dbl("absorption", 6.0D);
        int amplifier = Math.max(0, context.integer("resistance-amplifier", 1));

        plugin.states().setDefense(player.getUniqueId(), duration, reflection, absorption, context.weapon().id());

        // 2.0：壁垒窗口内自身造成的伤害提升（armor-pierce，0 = 关闭）
        double armorPierce = context.dbl("armor-pierce", 0.0D);
        if (armorPierce > 0.0D) {
            plugin.states().setOffense(player.getUniqueId(), duration, armorPierce);
        }

        // 壁垒模式的取舍：用机动换硬度（给自己挂缓慢）
        int selfSlow = Math.max(0, context.integer("self-slow-amplifier", 0));
        if (selfSlow > 0) {
            PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
            if (slow != null) {
                player.addPotionEffect(new PotionEffect(slow,
                        context.integer("self-slow-ticks", duration), selfSlow, false, false, true));
            }
        }

        PotionEffectType resistance = plugin.versions().potionEffect("RESISTANCE");
        if (resistance != null) {
            player.addPotionEffect(new PotionEffect(resistance, duration, amplifier, false, true, true));
        }
        if (absorption > 0.0D) {
            SkillTargets.addAbsorption(player, absorption);
        }

        // 2.0：护主架势（ally-*）——把增益扩散给半径内的队友，0 = 只护自己
        spreadToAllies(context, player, duration, reflection, absorption, amplifier, resistance);

        plugin.fx().actionBar(player, plugin.config().messages().get("skill.guard-ready",
                "time", SkillManager.formatSeconds(duration / 20.0D)));
        plugin.fx().sound(context.str("sound", "BLOCK_ANVIL_LAND"), player, 0.8F, 1.2F);
        plugin.fx().particle(context.str("particle", "ENCHANTED_HIT"), player.getLocation().add(0.0D, 1.0D, 0.0D), 24, 0.5D);
        return SkillResult.SUCCESS;
    }

    /**
     * 把防御窗口与吸收扩散给半径内的友方（{@code ally-radius > 0} 时）。
     *
     * <p>队友拿到的同样是一整套防御窗口（含反射比例），而不只是吸收——否则"护主架势"
     * 对队友只是个护盾，与它的语义不符。{@code ally-absorption} 缺省取自身的 50%。</p>
     */
    private void spreadToAllies(SkillContext context, Player player, int duration,
                                double reflection, double absorption, int amplifier,
                                PotionEffectType resistance) {
        double allyRadius = context.dbl("ally-radius", 0.0D);
        if (allyRadius <= 0.0D) {
            return;
        }
        double allyAbsorption = context.dbl("ally-absorption", absorption * 0.5D);
        int allyAmplifier = Math.max(0, context.integer("ally-resistance-amplifier", amplifier));
        for (Player ally : player.getWorld().getPlayers()) {
            if (ally.equals(player)
                    || ally.getLocation().distanceSquared(player.getLocation()) > allyRadius * allyRadius
                    || !isFriendly(player, ally)) {
                continue;
            }
            plugin.states().setDefense(ally.getUniqueId(), duration, reflection, allyAbsorption,
                    context.weapon().id());
            if (resistance != null) {
                ally.addPotionEffect(new PotionEffect(resistance, duration, allyAmplifier, false, true, true));
            }
            if (allyAbsorption > 0.0D) {
                SkillTargets.addAbsorption(ally, allyAbsorption);
            }
            plugin.fx().particle(context.str("particle", "ENCHANTED_HIT"),
                    ally.getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.4D);
        }
    }

    /** 同队（PVE 里同一房间全员都算友方）。 */
    private boolean isFriendly(Player owner, Player other) {
        var room = plugin.rooms().roomOf(owner);
        if (room == null || plugin.rooms().roomOf(other) != room) {
            return false;
        }
        if (room.isPve()) {
            return true;
        }
        var team = room.teamOf(owner.getUniqueId());
        return team != null && team == room.teamOf(other.getUniqueId());
    }
}
