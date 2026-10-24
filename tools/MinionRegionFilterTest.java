import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.CuboidRegion;

import java.util.List;

/**
 * normal / mixed 月人刷新区筛选的离线断言（无需启动服务器）。
 *
 * <p>背景：1.6.0 起 normal 与 mixed 两类刷新区各走一条独立计时循环，
 * 筛选错了会导致一条循环刷错区。这里断言 ArenaDef 的三个列表互不串区。</p>
 */
public final class MinionRegionFilterTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        ArenaDef arena = new ArenaDef(null, "filter-test");
        arena.setMinionRegion(1, region(1), "normal");
        arena.setMinionRegion(2, region(2), "mixed");
        arena.setMinionRegion(3, region(3), "normal");
        // 未显式写标签的区默认 mixed（旧行为兼容）
        arena.setMinionRegion(4, region(4));

        List<CuboidRegion> all = arena.minionRegionList();
        List<CuboidRegion> normal = arena.normalMinionRegionList();
        List<CuboidRegion> mixed = arena.mixedMinionRegionList();

        check("全部区 4 个", all.size() == 4);
        check("normal 区只有 1、3（按编号升序）",
                normal.size() == 2 && indexOf(normal, 1) == 0 && indexOf(normal, 3) == 1);
        check("mixed 区只有 2、4（未写标签默认 mixed）",
                mixed.size() == 2 && indexOf(mixed, 2) == 0 && indexOf(mixed, 4) == 1);
        check("normal 与 mixed 区不重叠",
                normal.stream().noneMatch(mixed::contains));
        check("两个子列表合并 = 全部区",
                normal.size() + mixed.size() == all.size());
        check("mixedMinionRegionCount 与 mixed 列表一致",
                arena.mixedMinionRegionCount() == mixed.size());

        // 只有 normal 区：mixed 列表为空（精英波 / 大波次不刷新，mixed 循环不启动）
        ArenaDef onlyNormal = new ArenaDef(null, "only-normal");
        onlyNormal.setMinionRegion(1, region(1), "normal");
        onlyNormal.setMinionRegion(2, region(2), "normal");
        check("全 normal 场地的 mixed 列表为空", onlyNormal.mixedMinionRegionList().isEmpty());
        check("全 normal 场地的 normal 列表齐全", onlyNormal.normalMinionRegionList().size() == 2);

        // 只有 mixed 区：normal 列表为空（normal 循环不启动）
        ArenaDef onlyMixed = new ArenaDef(null, "only-mixed");
        onlyMixed.setMinionRegion(1, region(1), "mixed");
        check("全 mixed 场地的 normal 列表为空", onlyMixed.normalMinionRegionList().isEmpty());
        check("全 mixed 场地的 mixed 列表齐全", onlyMixed.mixedMinionRegionList().size() == 1);

        System.out.println("MinionRegionFilterTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static CuboidRegion region(int marker) {
        return new CuboidRegion("world", marker, 0, 0, marker + 1, 1, 1);
    }

    /** 用 X 坐标反推区号（region(int) 里把 marker 放在了 minX 上）。 */
    private static int indexOf(List<CuboidRegion> regions, int marker) {
        for (int i = 0; i < regions.size(); i++) {
            if ((int) regions.get(i).minX() == marker) {
                return i;
            }
        }
        return -1;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL: " + name);
        }
    }
}
