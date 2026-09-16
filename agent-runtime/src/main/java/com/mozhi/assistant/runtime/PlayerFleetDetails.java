package com.mozhi.assistant.runtime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
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
import java.util.Set;
import java.util.function.Function;

/** Invoked by the tool on the main thread. Reads Global each time; no cached fleet state. */
final class PlayerFleetDetails {
    private PlayerFleetDetails() { }

    static String read() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return "当前没有已载入的玩家舰队。";
        }
        List<FleetMemberAPI> members = Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        StringBuilder out = new StringBuilder("【当前玩家舰队详情】\n");
        long ships = members.stream().filter(member -> !member.isFighterWing()).count();
        out.append("舰队：").append(fleet.getName())
                .append("；舰船：").append(ships).append("；独立战机联队：").append(members.size() - ships)
                .append("；舰队点数 FP：").append(fleet.getFleetPoints()).append('\n');
        out.append("位置：").append(fleet.getContainingLocation() == null ? "未知" : fleet.getContainingLocation().getName())
                .append("；指挥官：").append(captain(fleet.getCommander())).append('\n');
        CargoAPI cargo = fleet.getCargo();
        if (cargo != null) {
            out.append("星币：").append(number(cargo.getCredits().get()))
                    .append("；补给：").append(number(cargo.getSupplies()))
                    .append("；燃料：").append(number(cargo.getFuel())).append('/').append(number(cargo.getMaxFuel()))
                    .append("；船员：").append(cargo.getCrew()).append("；陆战队员：").append(cargo.getMarines())
                    .append("；货舱占用：").append(number(cargo.getSpaceUsed())).append('/').append(number(cargo.getMaxCapacity())).append('\n');
        }
        for (int i = 0; i < members.size(); i++) {
            FleetMemberAPI member = members.get(i);
            out.append("\n【").append(i + 1).append("】").append(member.getShipName())
                    .append(member.isFlagship() ? " [旗舰]" : "")
                    .append(member.isMothballed() ? " [封存]" : "")
                    .append(member.isFighterWing() ? " [独立战机联队]" : "").append('\n');
            try {
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
                    continue;
                }
                appendVariant(out, variant);
                for (String slot : variant.getModuleSlots()) {
                    ShipVariantAPI module = variant.getModuleVariant(slot);
                    if (module == null) continue;
                    out.append("子模块 ").append(slot).append("：\n");
                    appendVariant(out, module);
                }
            } catch (RuntimeException e) {
                // Keep information from other ships if a modded member has incomplete specs.
                out.append("该成员部分详情不可用：").append(e.getClass().getSimpleName()).append('\n');
            }
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

    private static String captain(PersonAPI person) {
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

    private static String number(float value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String percent(float value) { return String.format(Locale.ROOT, "%.0f%%", value * 100); }
}
