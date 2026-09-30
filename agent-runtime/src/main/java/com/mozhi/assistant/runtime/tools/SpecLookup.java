package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 索引只保存当前批次的规格引用；匹配后才调用对应类别的详情方法。 */
final class SpecLookup {
    enum Category {
        HULL("舰船（舰型）"), WEAPON("武器"), HULL_MOD("船插（舰船插件）"),
        SHIP_SYSTEM("战术系统"), COMMODITY("物品 / 普通商品"), SPECIAL_ITEM("物品 / 特殊物品");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    // 类别参与身份判定，避免不同类别的同一个 ID 在去重时相互覆盖。
    private record Key(Category category, String id) { }
    private record Entry(Key key, String name, Supplier<String> details) { }

    private final Map<String, Map<Key, Entry>> byId = new LinkedHashMap<>();
    private final Map<String, Map<Key, Entry>> byName = new LinkedHashMap<>();
    private final Map<Category, String> unavailable = new EnumMap<>(Category.class);
    private final Map<Category, Integer> incompleteEntries = new EnumMap<>(Category.class);

    private SpecLookup(Set<Category> categories) {
        var settings = Global.getSettings();
        load(categories, Category.HULL, () -> register(Category.HULL, settings.getAllShipHullSpecs(),
                ShipHullSpecAPI::getHullId, ShipHullSpecAPI::getHullName, HullSpecDetails::read, SpecLookup::hullAliases));
        load(categories, Category.WEAPON, () -> register(Category.WEAPON, settings.getActuallyAllWeaponSpecs(),
                spec -> spec.getWeaponId(), spec -> spec.getWeaponName(), SpecDetails::readWeapon));
        load(categories, Category.HULL_MOD, () -> register(Category.HULL_MOD, settings.getAllHullModSpecs(),
                spec -> spec.getId(), spec -> spec.getDisplayName(), SpecDetails::readHullMod));
        load(categories, Category.SHIP_SYSTEM, () -> register(Category.SHIP_SYSTEM, settings.getAllShipSystemSpecs(),
                spec -> spec.getId(), spec -> spec.getName(), SpecDetails::readShipSystem));
        load(categories, Category.COMMODITY, () -> register(Category.COMMODITY, settings.getAllCommoditySpecs(),
                spec -> spec.getId(), spec -> spec.getName(), SpecDetails::readCommodity));
        load(categories, Category.SPECIAL_ITEM, () -> register(Category.SPECIAL_ITEM, settings.getAllSpecialItemSpecs(),
                spec -> spec.getId(), spec -> spec.getName(), SpecDetails::readSpecialItem));
    }

    /** 必须在游戏主线程执行；每项查询可命中多个类别。 */
    static String read(List<String> queries, Set<Category> categories) {
        SpecLookup lookup = new SpecLookup(categories);
        StringBuilder out = new StringBuilder("以下为游戏已加载的基础规格，未计入具体实例、技能或战斗中的动态修正。\n");
        lookup.appendWarnings(out);
        Map<Key, Integer> returnedAt = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            out.append("\n查询 ").append(i + 1).append("：").append(query == null ? "（空）" : query).append('\n');
            if (query == null || query.isBlank()) {
                out.append("名称或 ID 不能为空。\n");
                continue;
            }
            List<Entry> matches = lookup.find(query);
            if (matches.isEmpty()) {
                out.append("未找到匹配规格，请提供完整名称或规格 ID。\n");
                continue;
            }
            out.append("匹配数量：").append(matches.size()).append('\n');
            for (Entry entry : matches) {
                appendDetails(out, entry, i + 1, returnedAt);
            }
        }
        return out.toString();
    }

    private static void appendDetails(StringBuilder out, Entry entry, int queryNumber, Map<Key, Integer> returnedAt) {
        out.append("\n类别：").append(entry.key.category.label).append(" [").append(entry.key.category).append("]\n")
                .append("名称：").append(entry.name == null || entry.name.isBlank() ? "未提供" : entry.name).append('\n')
                .append("ID：").append(entry.key.id).append('\n');
        Integer previous = returnedAt.get(entry.key);
        if (previous != null) {
            out.append("详情见查询 ").append(previous).append("。\n");
            return;
        }
        try {
            out.append(entry.details.get());
            returnedAt.put(entry.key, queryNumber);
        } catch (RuntimeException exception) {
            out.append("详情读取失败：").append(exception.getClass().getSimpleName()).append('\n');
        }
    }

    record Match(Category category, String id, String name) { }

    /** 购买查询仅复用身份匹配，不生成冗长的规格详情。 */
    static List<Match> resolve(String query) {
        Set<Category> categories = EnumSet.allOf(Category.class);
        String term = query;
        int separator = query.indexOf(':');
        if (separator > 0) {
            try {
                categories = EnumSet.of(Category.valueOf(query.substring(0, separator).strip().toUpperCase(Locale.ROOT)));
                term = query.substring(separator + 1);
            } catch (IllegalArgumentException ignored) {
                // 未知前缀仍按原始 ID 处理，允许模组 ID 自身含冒号。
            }
        }
        SpecLookup lookup = new SpecLookup(categories);
        if (!lookup.unavailable.isEmpty() || !lookup.incompleteEntries.isEmpty()) {
            throw new IllegalStateException("规格索引不完整，无法可靠识别购买对象。");
        }
        return lookup.find(term).stream()
                .map(entry -> new Match(entry.key.category, entry.key.id, entry.name)).toList();
    }

    private List<Entry> find(String query) {
        Map<Key, Entry> result = new LinkedHashMap<>();
        Set<Category> idCategories = EnumSet.noneOf(Category.class);
        Map<Key, Entry> ids = byId.get(normalize(query));
        if (ids != null) {
            result.putAll(ids);
            ids.keySet().forEach(key -> idCategories.add(key.category));
        }
        Map<Key, Entry> names = byName.get(nameKey(query));
        if (names != null) {
            for (Entry entry : names.values()) {
                // 仅在同类别内 ID 优先，不吞掉其他类别中名称相同的对象。
                if (!idCategories.contains(entry.key.category)) {
                    result.put(entry.key, entry);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private <T> void register(Category category, Collection<T> specs, Function<T, String> id,
                              Function<T, String> name, Function<T, String> details) {
        register(category, specs, id, name, details, ignored -> List.of());
    }

    private <T> void register(Category category, Collection<T> specs, Function<T, String> id,
                              Function<T, String> name, Function<T, String> details, Function<T, List<String>> aliases) {
        for (T spec : specs) {
            try {
                String specId = id.apply(spec);
                if (specId == null || specId.isBlank()) {
                    incompleteEntries.merge(category, 1, Integer::sum);
                    continue;
                }
                Entry entry = new Entry(new Key(category, specId), name.apply(spec), () -> details.apply(spec));
                byId.computeIfAbsent(normalize(specId), ignored -> new LinkedHashMap<>()).put(entry.key, entry);
                alias(entry, entry.name);
                for (String alias : aliases.apply(spec)) {
                    alias(entry, alias);
                }
            } catch (RuntimeException exception) {
                incompleteEntries.merge(category, 1, Integer::sum);
            }
        }
    }

    private void alias(Entry entry, String name) {
        if (name != null && !name.isBlank()) {
            byName.computeIfAbsent(nameKey(name), ignored -> new LinkedHashMap<>()).put(entry.key, entry);
        }
    }

    private void load(Set<Category> categories, Category category, Runnable loader) {
        if (!categories.contains(category)) {
            return;
        }
        try {
            loader.run();
        } catch (RuntimeException exception) {
            unavailable.put(category, exception.getClass().getSimpleName());
        }
    }

    private void appendWarnings(StringBuilder out) {
        unavailable.forEach((category, error) -> out.append("注意：").append(category.label)
                .append("目录读取不完整（").append(error).append("），该类别结果可能缺失。\n"));
        incompleteEntries.forEach((category, count) -> out.append("注意：").append(category.label)
                .append("有 ").append(count).append(" 项规格索引不完整，部分名称或 ID 可能无法匹配。\n"));
    }

    private static List<String> hullAliases(ShipHullSpecAPI spec) {
        List<String> aliases = new ArrayList<>();
        aliases.add(spec.getHullNameWithDashClass());
        aliases.add(spec.getNameWithDesignationWithDashClass());
        String name = spec.getHullName();
        if (name != null && !name.isBlank()) {
            aliases.add(name + "级");
            if (spec.getDesignation() != null && !spec.getDesignation().isBlank()) {
                aliases.add(name + spec.getDesignation());
                aliases.add(name + "级" + spec.getDesignation());
            }
        }
        return aliases;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static String nameKey(String value) {
        return normalize(value).replaceAll("\\s+", "");
    }
}
