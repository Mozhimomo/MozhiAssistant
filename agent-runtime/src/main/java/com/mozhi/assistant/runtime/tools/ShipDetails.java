package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** 复用的单舰详情读取器；必须在游戏主线程读取实时 FleetMemberAPI。 */
public final class ShipDetails {
    private ShipDetails() { }

    public static String read(FleetMemberAPI member) {
        Objects.requireNonNull(member, "member");
        StringBuilder out = new StringBuilder();
        try {
            out.append(member.getShipName())
                    .append(member.isFlagship() ? " [旗舰]" : "")
                    .append(member.isMothballed() ? " [封存]" : "")
                    .append(member.isFighterWing() ? " [独立战机联队]" : "").append('\n');
            out.append("舰船 ID：").append(member.getId()).append('\n');
            ShipHullSpecAPI hull = member.getHullSpec();
            out.append("船体：").append(hull == null ? member.getHullId() : hull.getHullName())
                    .append(" [").append(member.getHullId()).append(']')
                    .append("；级别：").append(hull == null ? "未知" : hull.getHullSize())
                    .append("；部署点 DP：").append(number(member.getDeploymentPointsCost())).append('\n');
            out.append("舰长：").append(captain(member.getCaptain()))
                    .append("；战备 CR：").append(member.getRepairTracker() == null ? "未知" : percent(member.getRepairTracker().getCR()))
                    .append("；船体完整度：").append(member.getStatus() == null ? "未知" : percent(member.getStatus().getHullFraction())).append('\n');
            ShipVariantAPI variant = member.getVariant();
            if (variant == null) {
                out.append("装配数据不可用。\n");
                return out.toString();
            }
            appendVariant(out, variant);
            for (String slot : variant.getModuleSlots()) {
                ShipVariantAPI module = variant.getModuleVariant(slot);
                if (module == null) continue;
                out.append("子模块 ").append(slot).append("：\n");
                appendVariant(out, module);
            }
        } catch (RuntimeException exception) {
            // 模组舰船缺少某项数据时，保留已读取的字段，不影响同批其他舰船。
            out.append("该成员部分详情不可用：").append(exception.getClass().getSimpleName()).append('\n');
        }
        return out.toString();
    }

    private static void appendVariant(StringBuilder out, ShipVariantAPI variant) {
        out.append("装配：").append(variant.getDisplayName()).append(" [").append(variant.getHullVariantId()).append(']')
                .append("；散幅器：").append(variant.getNumFluxVents())
                .append("；电容：").append(variant.getNumFluxCapacitors()).append('\n');
        Map<String, String> bySlot = new LinkedHashMap<>();
        if (variant.getHullSpec() != null) bySlot.putAll(variant.getHullSpec().getBuiltInWeapons());
        for (String slot : variant.getFittedWeaponSlots()) bySlot.put(slot, variant.getWeaponId(slot));
        out.append("武器（含内置）：").append(counted(bySlot.values(), id -> Global.getSettings().getWeaponSpec(id).getWeaponName())).append('\n');
        out.append("战机联队：").append(counted(variant.getFittedWings(), id -> Global.getSettings().getFighterWingSpec(id).getWingName())).append('\n');
        Set<String> mods = new LinkedHashSet<>(variant.getHullMods());
        if (variant.getHullSpec() != null) mods.addAll(variant.getHullSpec().getBuiltInMods());
        List<String> modNames = new ArrayList<>();
        for (String id : mods) {
            String label = named(id, key -> Global.getSettings().getHullModSpec(key).getDisplayName());
            if (variant.getSMods().contains(id) || variant.getSModdedBuiltIns().contains(id)) label += " [S改]";
            if (variant.getHullSpec() != null && variant.getHullSpec().getBuiltInMods().contains(id)) label += " [内置]";
            if (variant.getSuppressedMods().contains(id)) label += " [被抑制]";
            modNames.add(label);
        }
        out.append("舰船插件：").append(modNames.isEmpty() ? "无" : String.join("、", modNames)).append('\n');
    }

    static String captain(PersonAPI person) {
        if (person == null || person.isDefault()) return "未指派";
        return person.getNameString() + (person.isAICore() ? " [AI核心：" + person.getAICoreId() + "]" : "")
                + (person.getStats() == null ? "" : " Lv." + person.getStats().getLevel());
    }

    private static String counted(Collection<String> ids, Function<String, String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : ids) if (id != null && !id.isBlank()) counts.merge(id, 1, Integer::sum);
        List<String> labels = new ArrayList<>();
        counts.forEach((id, count) -> labels.add(named(id, names) + " ×" + count));
        return labels.isEmpty() ? "无" : String.join("、", labels);
    }

    private static String named(String id, Function<String, String> names) {
        try {
            String name = names.apply(id);
            if (name != null && !name.isBlank()) return name + " [" + id + "]";
        } catch (RuntimeException ignored) { /* Preserve ID when a mod's display spec is absent. */ }
        return id;
    }

    static String number(float value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String percent(float value) { return String.format(Locale.ROOT, "%.0f%%", value * 100); }
}
