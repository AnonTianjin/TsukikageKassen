package com.taketori.kassen.paper.editor;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 武器数据编辑 GUI：管理员在服务器里改数值，不用去动 weapons.yml。
 *
 * <p>三层菜单：</p>
 * <ol>
 *   <li><b>武器列表</b>：所有武器，点一个进详情；</li>
 *   <li><b>武器详情</b>：属性（攻击力/攻速/移速）、材质、显示名、各按键槽位的技能；</li>
 *   <li><b>技能参数</b>：该槽位的 type / cooldown / 全部 params。</li>
 * </ol>
 *
 * <p>点可编辑的项 → 关闭菜单、在聊天栏输入新值（60 秒内有效，输入 cancel 取消）→
 * 写回 weapons.yml（<b>保留注释</b>）→ 自动 reload → 回到刚才那一页。</p>
 *
 * <p>为什么用聊天栏输入而不是铁砧界面：铁砧要额外依赖且中文字符受限、还容易被客户端行为打断；
 * 聊天输入对数字/文本/列表都通用，并且可以立刻给出"改成什么了"的回执。</p>
 */
public final class WeaponEditor {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 聊天输入的等待时间。 */
    private static final long PENDING_TIMEOUT_MILLIS = 60_000L;

    /** 可编辑的槽位引用（modeId 为 null 表示武器级）。 */
    private record SlotRef(String modeId, SkillSlot slot, SkillDef def) {
    }

    /** 待输入的编辑目标。 */
    public record EditTarget(List<String> path, String label, String current,
                             String weaponId, String modeId, SkillSlot slot, String hint) {
    }

    private record Pending(EditTarget target, long expiresAt) {
    }

    private final TaketoriPlugin plugin;
    private final WeaponYamlEditor yaml;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public WeaponEditor(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.yaml = new WeaponYamlEditor(new File(plugin.getDataFolder(), "weapons.yml"));
    }

    public WeaponYamlEditor yaml() {
        return yaml;
    }

    // ---------------------------------------------------------------- 菜单 1：武器列表

    public void open(Player player) {
        EditorHolder holder = new EditorHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, MINI.deserialize("<dark_gray>武器数据编辑"));
        holder.bind(inventory);

        int index = 0;
        for (String weaponId : plugin.config().weapons().ids()) {
            WeaponDef weapon = plugin.config().weapons().get(weaponId);
            if (weapon == null || index >= 45) {
                continue;
            }
            ItemStack icon = new ItemStack(materialOf(weapon));
            icon.editMeta(meta -> {
                meta.displayName(MINI.deserialize(weapon.display()));
                meta.lore(List.of(
                        MINI.deserialize("<dark_gray>id: <white>" + weapon.id()),
                        MINI.deserialize("<dark_gray>角色: <white>"
                                + (weapon.characterId() == null || weapon.characterId().isBlank()
                                ? "-" : weapon.characterId())),
                        MINI.deserialize("<dark_gray>材质: <white>" + weapon.materialName()),
                        MINI.deserialize("<dark_gray>技能槽: <white>" + slotRefs(weapon).size() + " 个"),
                        MINI.deserialize(""),
                        MINI.deserialize("<yellow>▶ 点击编辑")));
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            });
            inventory.setItem(index, icon);
            final String id = weaponId;
            holder.onClick(index, clicker -> openWeapon(clicker, id));
            index++;
        }

        inventory.setItem(49, button(Material.BOOK, "<yellow>说明",
                "<gray>点武器 → 改属性 / 技能参数",
                "<gray>点数值 → 在聊天栏输入新值",
                "<gray>输入 <white>cancel</white> 取消",
                "<dark_gray>改动会写回 weapons.yml（保留注释）"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        holder.onClick(53, Player::closeInventory);

        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 菜单 2：武器详情

    public void openWeapon(Player player, String weaponId) {
        WeaponDef weapon = plugin.config().weapons().get(weaponId);
        if (weapon == null) {
            say(player, "<red>找不到武器：" + weaponId);
            open(player);
            return;
        }
        EditorHolder holder = new EditorHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>编辑 · <white>" + weapon.id()));
        holder.bind(inventory);

        ItemStack info = new ItemStack(materialOf(weapon));
        info.editMeta(meta -> {
            meta.displayName(MINI.deserialize(weapon.display()));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<dark_gray>id: <white>" + weapon.id()));
            lore.add(MINI.deserialize("<dark_gray>材质: <white>" + weapon.materialName()));
            lore.add(MINI.deserialize("<dark_gray>默认模式: <white>"
                    + (weapon.defaultMode().isBlank() ? "-" : weapon.defaultMode())));
            weapon.attributes().forEach((key, value) ->
                    lore.add(MINI.deserialize("<dark_gray>属性 " + key + ": <white>" + value)));
            if (weapon.hasHitEffects()) {
                lore.add(MINI.deserialize("<dark_gray>命中附加: <white>" + weapon.hitEffects().keySet()));
            }
            inventory.setItem(4, iconOf(info, lore));
        });

        // 技能槽：武器级 + 各模式级
        int slot = 9;
        for (SlotRef ref : slotRefs(weapon)) {
            if (slot >= 45) {
                break;
            }
            String title = (ref.modeId() == null ? "" : "<gold>" + ref.modeId() + " <dark_gray>· ")
                    + "<white>" + slotName(ref.slot());
            ItemStack item = button(Material.PAPER, title,
                    "<dark_gray>type: <white>" + ref.def().type(),
                    "<dark_gray>cooldown: <white>" + ref.def().cooldownSeconds() + "s",
                    "<dark_gray>参数: <white>" + ref.def().params().size() + " 项",
                    "",
                    "<yellow>▶ 点击编辑参数");
            inventory.setItem(slot, item);
            final SlotRef target = ref;
            holder.onClick(slot, clicker -> openSkill(clicker, weapon.id(), target.modeId(), target.slot()));
            slot++;
        }

        // 属性 / 材质 / 显示名
        attributeButton(holder, inventory, weapon, 45, "attack-damage", "攻击力");
        attributeButton(holder, inventory, weapon, 46, "attack-speed", "攻击速度");
        attributeButton(holder, inventory, weapon, 47, "movement-speed", "移动速度");

        inventory.setItem(48, button(Material.ANVIL, "<white>材质 <dark_gray>(material)",
                "<dark_gray>当前: <white>" + weapon.materialName(),
                "<gray>输入原版材质名，例如 DIAMOND_SWORD",
                "",
                "<yellow>▶ 点击修改"));
        holder.onClick(48, clicker -> beginEdit(clicker, new EditTarget(
                List.of(weapon.id(), "material"), "material", weapon.materialName(),
                weapon.id(), null, null, "填原版 Material 名，例如 DIAMOND_SWORD")));

        inventory.setItem(49, button(Material.NAME_TAG, "<white>显示名 <dark_gray>(display)",
                "<gray>支持 MiniMessage 标签，例如 <white><gold>陨星锤",
                "<dark_gray>当前: <white>" + weapon.display(),
                "",
                "<yellow>▶ 点击修改"));
        holder.onClick(49, clicker -> beginEdit(clicker, new EditTarget(
                List.of(weapon.id(), "display"), "display", weapon.display(),
                weapon.id(), null, null, "可以直接带 MiniMessage 标签，例如 <gold>陨星锤")));

        inventory.setItem(50, button(Material.CHEST, "<green>给我发一把",
                "<gray>立刻用当前配置生成这把武器",
                "<dark_gray>改完数值后用它马上试"));
        holder.onClick(50, clicker -> {
            clicker.getInventory().addItem(plugin.items().create(weapon, clicker));
            say(clicker, "<green>已发放 <white>" + weapon.display());
        });

        inventory.setItem(51, button(Material.ARROW, "<yellow>返回武器列表", "<gray>点一下返回"));
        holder.onClick(51, this::open);

        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        holder.onClick(53, Player::closeInventory);

        player.openInventory(inventory);
    }

    private void attributeButton(EditorHolder holder, Inventory inventory, WeaponDef weapon,
                                 int slot, String key, String label) {
        double value = weapon.attribute(key, 0.0D);
        boolean present = weapon.attributes().containsKey(key);
        inventory.setItem(slot, button(present ? Material.IRON_SWORD : Material.GRAY_DYE,
                "<white>" + label + " <dark_gray>(" + key + ")",
                "<dark_gray>当前: <white>" + (present ? String.valueOf(value) : "未设置（默认）"),
                present ? "<gray>写的是最终值（不用自己加原版基础值）" : "<gray>点击后填写即可新增这一项",
                "",
                "<yellow>▶ 点击修改"));
        holder.onClick(slot, clicker -> beginEdit(clicker, new EditTarget(
                List.of(weapon.id(), "attributes", key), key,
                present ? String.valueOf(value) : "0", weapon.id(), null, null,
                "attack-damage 写最终值；留空/0 表示不加成")));
    }

    // ---------------------------------------------------------------- 菜单 3：技能参数

    public void openSkill(Player player, String weaponId, String modeId, SkillSlot slot) {
        WeaponDef weapon = plugin.config().weapons().get(weaponId);
        if (weapon == null) {
            say(player, "<red>找不到武器：" + weaponId);
            open(player);
            return;
        }
        SkillDef def = modeId == null
                ? weapon.baseSkills().getOrDefault(slot, SkillDef.none())
                : (weapon.mode(modeId) == null
                ? SkillDef.none()
                : weapon.mode(modeId).skills().getOrDefault(slot, SkillDef.none()));
        if (!def.isPresent()) {
            say(player, "<red>该槽位没有绑定技能。");
            openWeapon(player, weaponId);
            return;
        }
        String title = weaponId + (modeId == null ? "" : " · " + modeId) + " · " + slotName(slot);
        EditorHolder holder = new EditorHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_gray>编辑 · <white>" + title));
        holder.bind(inventory);

        List<String> base = skillBasePath(weaponId, modeId, slot);

        ItemStack info = new ItemStack(Material.PAPER);
        info.editMeta(meta -> {
            meta.displayName(MINI.deserialize("<yellow>" + title));
            meta.lore(List.of(
                    MINI.deserialize("<dark_gray>type: <white>" + def.type()),
                    MINI.deserialize("<dark_gray>cooldown: <white>" + def.cooldownSeconds() + "s"),
                    MINI.deserialize("<dark_gray>参数: <white>" + def.params().size() + " 项"),
                    MINI.deserialize(""),
                    MINI.deserialize("<dark_gray>路径: " + String.join(".", base))));
        });
        inventory.setItem(4, info);

        int index = 9;
        // cooldown
        List<String> cooldownPath = new ArrayList<>(base);
        cooldownPath.add("cooldown");
        inventory.setItem(index, button(Material.CLOCK, "<white>cooldown <dark_gray>(秒)",
                "<dark_gray>当前: <white>" + def.cooldownSeconds(),
                "<gray>0 = 无冷却",
                "",
                "<yellow>▶ 点击修改"));
        holder.onClick(index, clicker -> beginEdit(clicker, new EditTarget(cooldownPath, "cooldown",
                String.valueOf(def.cooldownSeconds()), weaponId, modeId, slot, "单位是秒，可以是小数")));
        index++;

        // params
        for (Map.Entry<String, Object> entry : def.params().entrySet()) {
            if (index >= 45) {
                break;
            }
            String key = entry.getKey();
            String value = String.valueOf(entry.getValue());
            List<String> path = new ArrayList<>(base);
            path.add("params");
            path.add(key);
            String type = valueTypeName(entry.getValue());
            inventory.setItem(index, button(Material.LIME_DYE,
                    "<white>" + key + " <dark_gray>= <yellow>" + shorten(value),
                    "<dark_gray>当前值: <white>" + shorten(value),
                    "<dark_gray>类型: <white>" + type,
                    "<dark_gray>路径: " + String.join(".", path),
                    "",
                    "<yellow>▶ 点击修改"));
            holder.onClick(index, clicker -> beginEdit(clicker, new EditTarget(path, key, value,
                    weaponId, modeId, slot, "类型是 " + type + "；列表可以写成 [A, B, C]")));
            index++;
        }

        // 类型只读
        inventory.setItem(45, button(Material.BEDROCK, "<white>type <dark_gray>(只读)",
                "<dark_gray>当前: <white>" + def.type(),
                "<gray>换类型等于换实现，风险太大",
                "<dark_gray>需要换请在 weapons.yml 里手动改"));
        inventory.setItem(46, button(Material.BOOK, "<yellow>参数说明",
                "<gray>这里只列出已被技能读取的键",
                "<gray>新增键会追加到 params 末尾",
                "<dark_gray>写错的键会被配置校验点名"));

        inventory.setItem(49, button(Material.ARROW, "<yellow>返回武器", "<gray>点一下返回"));
        holder.onClick(49, clicker -> openWeapon(clicker, weaponId));

        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        holder.onClick(53, Player::closeInventory);

        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 聊天输入

    public boolean hasPending(Player player) {
        return player != null && pending.containsKey(player.getUniqueId());
    }

    /**
     * 玩家退出服务器时清掉未完成的编辑会话。会话原本带 60 秒超时，但只在本玩家
     * 再次发起聊天/交互时才惰性检查——开着编辑菜单直接退服的条目会一直留在
     * {@code pending} 里，这里由 {@code forgetPlayer} 在退出事件统一清掉。
     */
    public void forget(UUID uuid) {
        if (uuid != null) {
            pending.remove(uuid);
        }
    }

    /** 处理聊天栏输入；返回 true 表示这条消息被编辑器消费掉了。 */
    public boolean handleChat(Player player, String rawMessage) {
        if (player == null) {
            return false;
        }
        Pending entry = pending.get(player.getUniqueId());
        if (entry == null) {
            return false;
        }
        String text = rawMessage == null ? "" : rawMessage.trim();
        if (text.isEmpty() || "cancel".equalsIgnoreCase(text) || "取消".equals(text)) {
            pending.remove(player.getUniqueId());
            say(player, "<yellow>已取消编辑。");
            reopen(player, entry.target());
            return true;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            pending.remove(player.getUniqueId());
            say(player, "<red>编辑已超时（超过 60 秒），请重新点要修改的项。");
            return true;
        }
        pending.remove(player.getUniqueId());
        EditTarget target = entry.target();
        WeaponYamlEditor.Result result = yaml.set(target.path(), text, null);
        if (!result.ok()) {
            say(player, "<red>保存失败：" + result.message());
            reopen(player, target);
            return true;
        }
        plugin.reloadAll(null);
        say(player, "<green>✔ " + target.label() + "：<gray>" + target.current()
                + " <gray>→ <white>" + text + " <dark_gray>(" + result.message() + ")");
        say(player, "<dark_gray>数值已生效；武器属性写在物品上，已发放的武器要用「给我发一把」重新领才会更新。");
        if (plugin.config().debug()) {
            plugin.getLogger().info("[editor] " + player.getName() + " 修改 "
                    + String.join(".", target.path()) + " = " + text);
        }
        reopen(player, target);
        return true;
    }

    public void beginEdit(Player player, EditTarget target) {
        pending.put(player.getUniqueId(), new Pending(target, System.currentTimeMillis() + PENDING_TIMEOUT_MILLIS));
        player.closeInventory();
        say(player, "<gold>[武器编辑] <gray>请在聊天栏输入 <white>" + target.label()
                + "</white> 的新值 <dark_gray>（当前：" + target.current() + "）");
        if (target.hint() != null) {
            say(player, "<dark_gray>" + target.hint());
        }
        say(player, "<gray>输入 <white>cancel</white> 取消；60 秒内不输入会自动失效。");
    }

    public void forget(Player player) {
        if (player != null) {
            pending.remove(player.getUniqueId());
        }
    }

    private void reopen(Player player, EditTarget target) {
        if (!player.isOnline()) {
            return;
        }
        if (target.slot() != null && target.weaponId() != null) {
            openSkill(player, target.weaponId(), target.modeId(), target.slot());
        } else if (target.weaponId() != null) {
            openWeapon(player, target.weaponId());
        } else {
            open(player);
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 该武器所有有技能的槽位（武器级 + 各模式级）。 */
    private List<SlotRef> slotRefs(WeaponDef weapon) {
        List<SlotRef> result = new ArrayList<>();
        for (SkillSlot slot : SkillSlot.values()) {
            SkillDef def = weapon.baseSkills().get(slot);
            if (def != null && def.isPresent()) {
                result.add(new SlotRef(null, slot, def));
            }
        }
        for (Map.Entry<String, WeaponDef.ModeDef> entry : weapon.modes().entrySet()) {
            for (SkillSlot slot : SkillSlot.values()) {
                SkillDef def = entry.getValue().skills().get(slot);
                if (def != null && def.isPresent()) {
                    result.add(new SlotRef(entry.getKey(), slot, def));
                }
            }
        }
        return result;
    }

    private List<String> skillBasePath(String weaponId, String modeId, SkillSlot slot) {
        List<String> path = new ArrayList<>();
        path.add(weaponId);
        if (modeId != null) {
            path.add("modes");
            path.add(modeId);
        }
        path.add("skills");
        path.add(slot.key());
        return path;
    }

    private String slotName(SkillSlot slot) {
        return switch (slot) {
            case LEFT -> "左键";
            case RIGHT -> "右键";
            case SHIFT_RIGHT -> "Shift+右键";
            case Q -> "Q 键";
        };
    }

    private Material materialOf(WeaponDef weapon) {
        Material material = Material.matchMaterial(
                weapon.materialName() == null ? "" : weapon.materialName().toUpperCase(Locale.ROOT));
        return material == null || material.isAir() ? Material.STICK : material;
    }

    private String valueTypeName(Object value) {
        if (value instanceof Boolean) {
            return "布尔 true/false";
        }
        if (value instanceof Number) {
            return "数字";
        }
        return "文本";
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 28 ? text : text.substring(0, 28) + "…";
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(MINI.deserialize(line));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        });
        return item;
    }

    private ItemStack iconOf(ItemStack base, List<net.kyori.adventure.text.Component> lore) {
        base.editMeta(meta -> meta.lore(lore));
        return base;
    }

    private void say(Player player, String miniMessage) {
        player.sendMessage(MINI.deserialize(miniMessage));
    }
}
