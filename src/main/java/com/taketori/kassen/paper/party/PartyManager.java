package com.taketori.kassen.paper.party;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 派对（组队）管理：好友组队后整队进入同一个房间，开局分队时整组同队。
 *
 * <p>数据结构（参考 BedWars party 扩展点，按本项目风格简化）：</p>
 * <ul>
 *   <li>每个派对由房主（leader）持有：{@code leaderId → Party}；成员集合<b>包含房主</b>，
 *       遍历"全队玩家"时不需要额外拼装；</li>
 *   <li>{@code memberToLeader} 反向索引：O(1) 查某人属于哪个派对；</li>
 *   <li>邀请有有效期（默认 60 秒），过期自动失效；玩家已在派对时不能被邀请。</li>
 * </ul>
 *
 * <p>全部方法只应在主线程调用（指令与事件路径天然满足）。玩家退出服务器时
 * {@link #onQuit(UUID)} 自动移出派对；房主退出则把房主职位转给最早加入的成员，
 * 没有其他成员才解散。</p>
 */
public final class PartyManager {

    /** 邀请有效期（毫秒）。 */
    private static final long INVITE_TTL_MILLIS = 60_000L;

    /** 一个派对：房主 + 成员（含房主）+ 未过期邀请。 */
    public static final class Party {
        private final UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();
        private final Map<UUID, Long> invites = new HashMap<>();

        private Party(UUID leader) {
            this.leader = leader;
            this.members.add(leader);
        }

        public UUID leader() {
            return leader;
        }

        /** 全部成员（含房主），保持加入顺序。 */
        public Set<UUID> members() {
            return members;
        }

        /** 是否还有除房主外的成员。 */
        public boolean hasOtherMembers() {
            return members.size() > 1;
        }
    }

    private final TaketoriPlugin plugin;
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> memberToLeader = new HashMap<>();

    public PartyManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 查询

    /** 派对人数上限（含房主）。 */
    public int maxSize() {
        return Math.max(2, plugin.getConfig().getInt("party.max-size", 3));
    }

    /** 某人所在的派对；没有派队返回 null。 */
    public Party partyOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        UUID leader = memberToLeader.get(uuid);
        return leader == null ? null : parties.get(leader);
    }

    public boolean inParty(UUID uuid) {
        return partyOf(uuid) != null;
    }

    /** 某人所在派队的全部成员 id（没有派对时返回仅含自己的单元素列表）。 */
    public List<UUID> membersOf(UUID uuid) {
        Party party = partyOf(uuid);
        if (party == null) {
            List<UUID> single = new ArrayList<>(1);
            single.add(uuid);
            return single;
        }
        return new ArrayList<>(party.members());
    }

    // ---------------------------------------------------------------- 邀请 / 加入

    /** 房主向目标发起邀请。返回是否真的发出了（失败原因已提示给双方）。 */
    public boolean invite(Player leader, Player target) {
        if (leader == null || target == null || !leader.isOnline() || !target.isOnline()) {
            return false;
        }
        if (leader.getUniqueId().equals(target.getUniqueId())) {
            leader.sendMessage(msg("party.invite-self"));
            return false;
        }
        // 目标校验放在 ensureParty 之前：否则邀请失败会给发起者留下一个"幽灵单人派对"，
        // 他自己从此无法被任何人邀请（inParty 拦截），也没有超时清理
        if (inParty(target.getUniqueId())) {
            leader.sendMessage(msg("party.target-in-party", "player", target.getName()));
            return false;
        }
        Party party = ensureParty(leader);
        // 只有房主能邀请：成员点邀请（指令/GUI）一律拒绝，避免任何人都能往派对里拉人
        if (!party.leader.equals(leader.getUniqueId())) {
            leader.sendMessage(msg("party.not-leader"));
            return false;
        }
        if (party.members.size() >= maxSize()) {
            leader.sendMessage(msg("party.full", "max", maxSize()));
            return false;
        }
        party.invites.put(target.getUniqueId(), System.currentTimeMillis() + INVITE_TTL_MILLIS);
        leader.sendMessage(msg("party.invite-sent", "player", target.getName()));
        target.sendMessage(msg("party.invited", "player", leader.getName(),
                "size", party.members.size(), "max", maxSize()));
        return true;
    }

    /** 玩家接受某个房主的邀请。返回是否成功加入（失败原因已提示）。 */
    public boolean accept(Player player, UUID leaderId) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (inParty(player.getUniqueId())) {
            player.sendMessage(msg("party.already-in"));
            return false;
        }
        Party party = leaderId == null ? null : parties.get(leaderId);
        if (party == null) {
            player.sendMessage(msg("party.no-invite"));
            return false;
        }
        Long expiry = party.invites.remove(player.getUniqueId());
        if (expiry == null || expiry < System.currentTimeMillis()) {
            player.sendMessage(msg("party.no-invite"));
            return false;
        }
        if (party.members.size() >= maxSize()) {
            player.sendMessage(msg("party.full-join", "max", maxSize()));
            return false;
        }
        party.members.add(player.getUniqueId());
        memberToLeader.put(player.getUniqueId(), party.leader);
        broadcast(party, "party.joined", "player", nameOf(player.getUniqueId()),
                "size", party.members.size(), "max", maxSize());
        return true;
    }

    /**
     * 找出给该玩家的最近一个未过期邀请的房主 id（/taketori party accept 不带参数时用）。
     * 顺带清掉已过期的邀请。没有可用邀请返回 null。
     */
    public UUID pendingInviteLeader(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        UUID best = null;
        long bestExpiry = Long.MIN_VALUE;
        for (Party party : parties.values()) {
            Long expiry = party.invites.get(uuid);
            if (expiry == null) {
                continue;
            }
            if (expiry < now) {
                party.invites.remove(uuid);
                continue;
            }
            if (expiry > bestExpiry) {
                bestExpiry = expiry;
                best = party.leader;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 退出 / 解散

    /** 成员退出；房主退出自动转让给最早加入的成员，没有成员才解散。 */
    public void leave(UUID uuid) {
        Party party = partyOf(uuid);
        if (party == null) {
            return;
        }
        // 退出后是否还有其他成员，要在移除<b>之前</b>判断：
        // 两人派对房主退出时，先删再判会得到 size==1 → 走解散，剩余成员被无告知踢散
        boolean hasOthers = party.members.size() > 1;
        boolean wasLeader = party.leader.equals(uuid);
        party.members.remove(uuid);
        memberToLeader.remove(uuid);
        if (hasOthers) {
            if (wasLeader) {
                // 转让房主：最早加入的成员（LinkedHashSet 迭代序 = 加入序）
                UUID next = party.members.iterator().next();
                parties.remove(party.leader);
                Party transferred = new Party(next);
                transferred.members.addAll(party.members);
                for (UUID member : transferred.members) {
                    memberToLeader.put(member, next);
                }
                parties.put(next, transferred);
                broadcast(transferred, "party.leader-left", "player", nameOf(uuid),
                        "new-leader", nameOf(next));
            } else {
                broadcast(party, "party.member-left", "player", nameOf(uuid),
                        "size", party.members.size(), "max", maxSize());
            }
        } else {
            disband(party.leader);
        }
    }

    /** 房主踢人。 */
    public void kick(Player leader, UUID target) {
        Party party = partyOf(leader == null ? null : leader.getUniqueId());
        if (party == null || !party.leader.equals(leader.getUniqueId())) {
            if (leader != null) {
                leader.sendMessage(msg("party.not-leader"));
            }
            return;
        }
        if (target == null || target.equals(leader.getUniqueId()) || !party.members.contains(target)) {
            leader.sendMessage(msg("party.kick-not-member"));
            return;
        }
        party.members.remove(target);
        memberToLeader.remove(target);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            online.sendMessage(msg("party.kicked-you"));
        }
        broadcast(party, "party.kicked", "player", nameOf(target),
                "size", party.members.size(), "max", maxSize());
    }

    /** 解散派对（房主主动或只剩房主一人）。 */
    public void disband(UUID leaderId) {
        Party party = parties.remove(leaderId);
        if (party == null) {
            return;
        }
        for (UUID member : party.members) {
            memberToLeader.remove(member);
        }
        broadcastIds(party.members, "party.disbanded");
    }

    /** 玩家退出服务器：自动移出派对（房主退出走转让逻辑）。 */
    public void onQuit(UUID uuid) {
        if (inParty(uuid)) {
            leave(uuid);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 取自己的派对；没有就创建一个单人的（房主 = 自己）。 */
    private Party ensureParty(Player leader) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null) {
            party = new Party(leader.getUniqueId());
            parties.put(leader.getUniqueId(), party);
            memberToLeader.put(leader.getUniqueId(), leader.getUniqueId());
        }
        return party;
    }

    private void broadcast(Party party, String key, Object... placeholders) {
        broadcastIds(party.members, key, placeholders);
    }

    private void broadcastIds(Set<UUID> ids, String key, Object... placeholders) {
        Component message = msg(key, placeholders);
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private Component msg(String key, Object... placeholders) {
        return plugin.config().messages().prefixed(key, placeholders);
    }

    /** 成员显示名：在线取在线名，离线取缓存的（getOfflinePlayer(UUID) 不做网络查询）。 */
    private String nameOf(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "未知玩家" : name;
    }
}
