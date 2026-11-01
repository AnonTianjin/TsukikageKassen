import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Offline sanity check for weapons.yml / characters.yml.
 *
 * Nothing here needs a running server: materials and entity types come from the Bukkit enums,
 * while particles / sounds / potion effects / attributes are read as public static constant
 * names (they are enums on some versions and registry-backed interfaces on others).
 *
 * Checks performed:
 *   1. every weapon's `character:` exists in characters.yml, and the link is bidirectional
 *   2. every character has at least one weapon and each referenced weapon exists
 *   3. material / projectile / particle / sound / slow-type / attribute names resolve
 *   4. skill types are among the registered implementations
 *   5. every skill param key is allowed for that type (read from ConfigValidator via reflection)
 *
 * Usage: java WeaponYamlCheck <resources-dir> [--verbose]
 */
public final class WeaponYamlCheck {

    private static final Set<String> SKILL_TYPES = Set.of(
            "melee_smash", "projectile", "shockwave", "rocket_jump", "pull", "reflect",
            "self_boost", "blink", "grapple", "shield_guard",
            "mirror_skill", "mirror_burst",
            // 2.0 版新增：易伤施加 / 破甲 / 兑现 / 场地 / 召唤
            "mark_apply", "armor_break", "echo_consume", "deploy_zone", "summon_ally");

    /** 2.0 三槽：左键 / 右键 / 双击潜行。 */
    private static final Set<String> SLOTS = Set.of("left", "right", "sneak");

    /** sneak 槽只允许移动类。 */
    private static final Set<String> MOVE_TYPES = Set.of("self_boost", "blink", "rocket_jump", "grapple");

    /** 右键可以留空的材质（原版拉弓 / 原版投掷）。 */
    private static final Set<String> VANILLA_RIGHT_MATERIALS = Set.of("BOW", "TRIDENT");

    /** 已下线的键：出现即报错，避免旧配置静默失效。 */
    private static final Set<String> REMOVED_TOP_KEYS = Set.of("modes", "default-mode");

    private static final Map<String, String> PARTICLE_KEYS = Map.of("particle", "particle", "hit-particle", "particle");
    private static final List<String> SOUND_KEYS = List.of("sound", "hit-sound", "miss-sound", "trail-sound");
    private static final List<String> POTION_KEYS = List.of("slow-type");

    private static final List<String> problems = new ArrayList<>();
    private static final Map<String, Integer> bindingCount = new TreeMap<>();
    private static int weaponCount = 0;
    private static int skillCount = 0;

    private static Set<String> materialNames;
    private static Set<String> particleNames;
    private static Set<String> soundNames;
    private static Set<String> potionNames;
    private static Set<String> attributeNames;
    private static Map<String, Set<String>> allowedParams;

    public static void main(String[] args) throws Exception {
        Path res = Path.of(args.length > 0 ? args[0] : "src/main/resources");
        boolean verbose = args.length > 1 && "--verbose".equals(args[1]);

        materialNames = null; // resolved through Material.matchMaterial instead
        particleNames = constNames(org.bukkit.Particle.class);
        soundNames = constNames(org.bukkit.Sound.class);
        potionNames = constNames(org.bukkit.potion.PotionEffectType.class);
        attributeNames = constNames(org.bukkit.attribute.Attribute.class);
        allowedParams = loadAllowedParams();

        Yaml yaml = new Yaml();
        Map<String, Object> weaponsRoot = loadYaml(yaml, res.resolve("weapons.yml"));
        Map<String, Object> charsRoot = loadYaml(yaml, res.resolve("characters.yml"));

        Map<String, Object> weapons = asMap(weaponsRoot.get("weapons"));
        Map<String, Object> characters = asMap(charsRoot.get("characters"));
        if (weapons.isEmpty()) {
            problems.add("weapons.yml: no weapon entries found under `weapons:`");
        }
        if (characters.isEmpty()) {
            problems.add("characters.yml: no entries found under `characters:`");
        }

        Set<String> claimedByCharacter = new HashSet<>();
        for (Map.Entry<String, Object> entry : characters.entrySet()) {
            String charId = entry.getKey();
            Map<String, Object> def = asMap(entry.getValue());
            Object listRaw = def.get("weapons");
            List<Object> list = asList(listRaw);
            if (list.isEmpty()) {
                problems.add("characters.yml [" + charId + "]: weapons list is empty");
            }
            for (Object item : list) {
                String weaponId = String.valueOf(item);
                if (!weapons.containsKey(weaponId)) {
                    problems.add("characters.yml [" + charId + "]: references missing weapon " + weaponId);
                    continue;
                }
                claimedByCharacter.add(weaponId);
                String declared = str(asMap(weapons.get(weaponId)).get("character"));
                if (!charId.equals(declared)) {
                    problems.add("weapons.yml [" + weaponId + "]: character is '" + declared
                            + "' but characters.yml [" + charId + "] lists it");
                }
            }
            Map<String, Object> attrs = asMap(def.get("attributes"));
            for (String key : attrs.keySet()) {
                if (!attributeNames.contains(normalize(key))) {
                    problems.add("characters.yml [" + charId + "]: unknown attribute " + key);
                }
            }
        }

        for (Map.Entry<String, Object> entry : weapons.entrySet()) {
            String weaponId = entry.getKey();
            Map<String, Object> def = asMap(entry.getValue());
            if (def.isEmpty()) {
                problems.add("weapons.yml [" + weaponId + "]: empty definition");
                continue;
            }
            weaponCount++;
            String owner = str(def.get("character"));
            if (owner == null || owner.isBlank()) {
                problems.add("weapons.yml [" + weaponId + "]: missing `character:`");
            } else if (!characters.containsKey(owner)) {
                problems.add("weapons.yml [" + weaponId + "]: character '" + owner + "' is not defined in characters.yml");
            }
            if (!claimedByCharacter.contains(weaponId)) {
                problems.add("weapons.yml [" + weaponId + "]: not listed in any character's weapons list (unreachable)");
            }

            String material = str(def.get("material"));
            if (material == null || Material.matchMaterial(material) == null) {
                problems.add("weapons.yml [" + weaponId + "]: material does not resolve: " + material);
            }
            Map<String, Object> attrs = asMap(def.get("attributes"));
            for (String key : attrs.keySet()) {
                if (!attributeNames.contains(normalize(key))) {
                    problems.add("weapons.yml [" + weaponId + "]: unknown attribute " + key);
                }
            }

            // 旧键必须点名：silently 失效比报错更难查
            for (String removed : REMOVED_TOP_KEYS) {
                if (def.containsKey(removed)) {
                    problems.add("weapons.yml [" + weaponId + "]: `" + removed
                            + ":` 已下线（2.0 每把武器只有一套三个技能）");
                }
            }

            // 被动：顶层 passive 段，type 必须为 passive
            Map<String, Object> passive = asMap(def.get("passive"));
            if (!passive.isEmpty()) {
                checkPassive(passive, weaponId, verbose);
            }

            // 三个按键槽
            Map<String, Object> skills = asMap(def.get("skills"));
            checkSlots(skills, weaponId, material, !passive.isEmpty(), verbose);
        }

        System.out.println("weapons: " + weaponCount + ", characters: " + characters.size()
                + ", skill bindings: " + skillCount);
        if (verbose) {
            System.out.println("bindings per weapon type:");
            bindingCount.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        }
        if (problems.isEmpty()) {
            System.out.println("OK: no configuration problems found");
            return;
        }
        System.out.println("PROBLEMS (" + problems.size() + "):");
        for (String problem : problems) {
            System.out.println("  - " + problem);
        }
        System.exit(1);
    }

    /** 校验一个武器级被动段。 */
    private static void checkPassive(Map<String, Object> passive, String weaponId, boolean verbose) {
        String where = "weapons.yml [" + weaponId + "] passive";
        String type = str(passive.get("type"));
        if (!"passive".equalsIgnoreCase(type)) {
            problems.add(where + ": type 必须是 passive（当前 " + type + "）");
            return;
        }
        checkParams("passive", asMap(passive.get("params")), where);
        if (verbose) {
            System.out.println("  " + where + " -> passive");
        }
    }

    private static void checkSlots(Map<String, Object> skills, String weaponId, String material,
                                   boolean hasPassive, boolean verbose) {
        String where = "weapons.yml [" + weaponId + "]";
        for (String removed : new String[]{"shift-right", "q"}) {
            if (skills.containsKey(removed)) {
                problems.add(where + ": `" + removed + ":` 已下线（第三槽改为 sneak，Q 槽随模式一并移除）");
            }
        }
        if (!hasPassive) {
            problems.add(where + ": 缺少 passive 段（每把武器都应有一个被动）");
        }

        for (Map.Entry<String, Object> slot : skills.entrySet()) {
            String slotKey = slot.getKey();
            if (!SLOTS.contains(slotKey)) {
                problems.add(where + ": unknown slot '" + slotKey + "' (expected left/right/sneak)");
                continue;
            }
            Map<String, Object> skill = asMap(slot.getValue());
            String type = str(skill.get("type"));
            if (type == null) {
                problems.add(where + " " + slotKey + ": missing `type:`");
                continue;
            }
            String normalizedType = type.toLowerCase(Locale.ROOT);
            if ("passive".equals(normalizedType)) {
                problems.add(where + " " + slotKey + ": 被动必须写在顶层 passive: 段，不能占用按键槽位");
                continue;
            }
            if (!SKILL_TYPES.contains(normalizedType)) {
                problems.add(where + " " + slotKey + ": unregistered skill type " + type);
                continue;
            }
            if ("sneak".equals(slotKey) && !MOVE_TYPES.contains(normalizedType)) {
                problems.add(where + " sneak: 只允许移动类技能（" + String.join(" / ", MOVE_TYPES)
                        + "），当前是 " + type);
            }
            skillCount++;
            bindingCount.merge(weaponId + "/" + type, 1, Integer::sum);
            checkParams(normalizedType, asMap(skill.get("params")), where + " " + slotKey + " (" + type + ")");
            if (verbose) {
                System.out.println("  " + where + " " + slotKey + " -> " + type);
            }
        }

        String upperMaterial = material == null ? "" : material.toUpperCase(Locale.ROOT);
        boolean bow = "BOW".equals(upperMaterial);
        if (bow && !skills.containsKey("left")) {
            problems.add(where + ": 弓没有近战普攻，必须在 left 绑定主动技能");
        }
        if (!skills.containsKey("right") && !VANILLA_RIGHT_MATERIALS.contains(upperMaterial)) {
            problems.add(where + ": right 缺失只允许 " + String.join("/", VANILLA_RIGHT_MATERIALS)
                    + "（留空表示走原版行为），当前材质 " + upperMaterial);
        }
        if (skills.size() > 3) {
            problems.add(where + ": 一把武器最多三个技能，当前 " + skills.size() + " 个");
        }
    }

    private static void checkParams(String type, Map<String, Object> params, String where) {
        Set<String> allowed = allowedParams.getOrDefault(type, Set.of());
        for (Map.Entry<String, Object> param : params.entrySet()) {
            String key = param.getKey();
            if (!allowed.contains(key)) {
                problems.add(where + ": unknown param '" + key + "'");
                continue;
            }
            Object value = param.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (PARTICLE_KEYS.containsKey(key) && !particleNames.contains(normalize(text))) {
                problems.add(where + ": particle does not resolve: " + text);
            }
            if (SOUND_KEYS.contains(key) && !soundNames.contains(normalize(text))) {
                problems.add(where + ": sound does not resolve: " + text);
            }
            if (POTION_KEYS.contains(key) && !potionNames.contains(normalize(text))) {
                problems.add(where + ": potion effect does not resolve: " + text);
            }
            if ("projectile".equals(key) && !entityTypeResolves(text)) {
                problems.add(where + ": projectile does not resolve: " + text);
            }
        }
    }

    private static boolean entityTypeResolves(String name) {
        try {
            EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ex) {
            try {
                EntityType.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
                return true;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }

    /** Public static constant names of a Bukkit enum / registry-backed interface. */
    private static Set<String> constNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                names.add(normalize(field.getName()));
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> loadAllowedParams() {
        try {
            Class<?> validator = Class.forName("com.taketori.kassen.config.ConfigValidator");
            Field field = validator.getDeclaredField("ALLOWED_PARAMS");
            field.setAccessible(true);
            Map<String, Set<String>> raw = (Map<String, Set<String>>) field.get(null);
            Map<String, Set<String>> copy = new HashMap<>();
            raw.forEach((k, v) -> copy.put(k.toLowerCase(Locale.ROOT), v));
            return copy;
        } catch (Throwable ex) {
            System.out.println("WARN: could not read ConfigValidator.ALLOWED_PARAMS (" + ex + ");"
                    + " compile the plugin first and put build/classes on the classpath");
            return Map.of();
        }
    }

    /** Same normalization as DefaultVersionAdapter: drop namespace, "generic" and separators. */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toLowerCase(Locale.ROOT);
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        text = text.replace("generic", "");
        return text.replace(".", "").replace("_", "").replace("-", "").trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Yaml yaml, Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                problems.add(file.getFileName() + ": top level is not a mapping");
                return Map.of();
            }
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new HashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
