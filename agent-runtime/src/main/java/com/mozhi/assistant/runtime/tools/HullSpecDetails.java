package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static com.mozhi.assistant.runtime.tools.SpecText.*;

/** 可复用的舰型详情读取器。调用方负责切换至游戏主线程。 */
public final class HullSpecDetails {
    private HullSpecDetails() {
    }

    public static String read(ShipHullSpecAPI spec) {
        StringBuilder out = new StringBuilder();
        out.append("\n=== ").append(spec.getHullName()).append(" [").append(spec.getHullId()).append("] ===\n");
        section(out, "基本资料", text -> identity(text, spec));
        section(out, "背景描述", text -> line(text, "正文", description(spec.getDescriptionId(), Description.Type.SHIP)));
        section(out, "船体与幅能", text -> performance(text, spec));
        section(out, "护盾与相位", text -> defense(text, spec));
        section(out, "机动性能", text -> mobility(text, spec));
        section(out, "后勤", text -> logistics(text, spec));
        section(out, "武器槽", text -> slots(text, spec));
        section(out, "内置装备", text -> builtIns(text, spec));
        section(out, "战术系统", text -> system(text, spec.getShipSystemId()));
        if (spec.getShipDefenseId() != null && !spec.getShipDefenseId().isBlank()) {
            section(out, "防御系统", text -> system(text, spec.getShipDefenseId()));
        }
        return out.toString();
    }

    private static void identity(StringBuilder out, ShipHullSpecAPI spec) {
        line(out, "完整名称", spec.getNameWithDesignationWithDashClass());
        line(out, "舰种", spec.getDesignation());
        line(out, "船体尺寸", spec.getHullSize());
        line(out, "制造商/科技体系", spec.getManufacturer());
        var mod = spec.getSourceMod();
        line(out, "来源模组", mod == null ? "未标注" : mod.getName() + " [" + mod.getId() + "] " + mod.getVersion());
        line(out, "基础舰型 ID", spec.getBaseHullId());
        line(out, "D 型父舰型 ID", spec.getDParentHullId());
        line(out, "修复后舰型 ID", spec.getRestoredToHullId());
        line(out, "是否 D 型", spec.isDHull());
        line(out, "是否航母", spec.isCarrier());
        line(out, "是否相位舰", spec.isPhase());
        line(out, "是否民用非航母", spec.isCivilianNonCarrier());
        line(out, "类型提示", spec.getHints());
        line(out, "标签", spec.getTags());
    }

    private static void performance(StringBuilder out, ShipHullSpecAPI spec) {
        line(out, "结构值", spec.getHitpoints());
        line(out, "装甲值", spec.getArmorRating());
        line(out, "幅能容量", spec.getFluxCapacity());
        line(out, "幅能耗散/秒", spec.getFluxDissipation());
        line(out, "基础装配点 OP", spec.getOrdnancePoints(null));
        line(out, "舰队点 FP", spec.getFleetPoints());
        line(out, "战机甲板数", spec.getFighterBays());
        line(out, "峰值作战时间/秒", spec.getNoCRLossSeconds());
        line(out, "部署战备消耗/%", spec.getCRToDeploy());
        line(out, "峰值后战备损失/秒（规格值）", spec.getCRLossPerSecond());
    }

    private static void defense(StringBuilder out, ShipHullSpecAPI spec) {
        line(out, "防御类型", spec.getDefenseType());
        var shield = spec.getShieldSpec();
        if (shield == null) {
            line(out, "护盾规格", null);
            return;
        }
        line(out, "护盾类型", shield.getType());
        if (spec.isPhase()) {
            line(out, "相位启动消耗（规格值）", shield.getPhaseCost());
            line(out, "相位维持消耗（规格值）", shield.getPhaseUpkeep());
        } else if (shield.getType() != com.fs.starfarer.api.combat.ShieldAPI.ShieldType.NONE) {
            line(out, "护盾覆盖角/度", shield.getArc());
            line(out, "每点伤害产生幅能", shield.getFluxPerDamageAbsorbed());
            line(out, "护盾维持消耗（规格值）", shield.getUpkeepCost());
        }
    }

    private static void mobility(StringBuilder out, ShipHullSpecAPI spec) {
        var engine = spec.getEngineSpec();
        if (engine == null) {
            line(out, "引擎规格", null);
            return;
        }
        line(out, "最高战斗航速", engine.getMaxSpeed());
        line(out, "加速度", engine.getAcceleration());
        line(out, "减速度", engine.getDeceleration());
        line(out, "最大转向速度/度每秒", engine.getMaxTurnRate());
        line(out, "转向加速度", engine.getTurnAcceleration());
    }

    private static void logistics(StringBuilder out, ShipHullSpecAPI spec) {
        line(out, "最少船员", spec.getMinCrew());
        line(out, "最大船员", spec.getMaxCrew());
        line(out, "货舱容量", spec.getCargo());
        line(out, "燃料容量", spec.getFuel());
        line(out, "每光年耗油", spec.getFuelPerLY());
        line(out, "每月维护补给", spec.getSuppliesPerMonth());
        line(out, "恢复所需补给", spec.getSuppliesToRecover());
        line(out, "基础价值/星币", spec.getBaseValue());
        if (spec.getLogisticsNAReason() != null && !spec.getLogisticsNAReason().isBlank()) {
            line(out, "后勤数据不适用原因", spec.getLogisticsNAReason());
        }
    }

    private static void slots(StringBuilder out, ShipHullSpecAPI spec) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        StringBuilder details = new StringBuilder();
        for (WeaponSlotAPI slot : spec.getAllWeaponSlotsCopy()) {
            if (slot.isDecorative()) {
                continue;
            }
            if (slot.isStationModule()) {
                line(details, "模块槽", slot.getId());
                continue;
            }
            if (!slot.isWeaponSlot() && !slot.isBuiltIn() && !slot.isSystemSlot()) {
                continue;
            }
            String kind = slot.getSlotSize() + " " + slot.getWeaponType();
            counts.merge(kind, 1, Integer::sum);
            details.append("- ").append(slot.getId()).append(": ").append(kind)
                    .append(slot.isHardpoint() ? " 固定炮座" : slot.isTurret() ? " 炮塔" : "")
                    .append("；射界 ").append(number(slot.getArc())).append("°")
                    .append("；朝向 ").append(number(slot.getAngle())).append("°")
                    .append(slot.isBuiltIn() ? "；内置" : "")
                    .append(slot.isSystemSlot() ? "；系统槽" : "")
                    .append(slot.isHidden() ? "；隐藏" : "").append('\n');
        }
        line(out, "槽位统计（不含装饰与模块槽）", counts.isEmpty() ? "无" : counts);
        out.append(details);
    }

    private static void builtIns(StringBuilder out, ShipHullSpecAPI spec) {
        line(out, "说明", "只列舰型自带装备，普通武器槽的配装取决于具体舰船或装配方案。");
        Map<String, String> weapons = spec.getBuiltInWeapons();
        if (weapons == null || weapons.isEmpty()) {
            line(out, "内置武器", "无");
        } else {
            for (var entry : new TreeMap<>(weapons).entrySet()) {
                line(out, "内置武器槽 " + entry.getKey(), named(entry.getValue(),
                        id -> Global.getSettings().getWeaponSpec(id).getWeaponName()));
            }
        }
        equipment(out, "内置战机", spec.getBuiltInWings(),
                id -> Global.getSettings().getFighterWingSpec(id).getWingName());
        equipment(out, "内置舰船插件", spec.getBuiltInMods(),
                id -> Global.getSettings().getHullModSpec(id).getDisplayName());
    }

    private static void system(StringBuilder out, String id) {
        if (id == null || id.isBlank()) {
            line(out, "系统", "无");
            return;
        }
        var spec = Global.getSettings().getShipSystemSpec(id);
        if (spec == null) {
            line(out, "系统规格", "未找到 [" + id + "]");
            return;
        }
        out.append(SpecDetails.readShipSystem(spec));
    }

    private static void equipment(StringBuilder out, String label, List<String> ids, Function<String, String> name) {
        if (ids == null || ids.isEmpty()) {
            line(out, label, "无");
            return;
        }
        for (String id : ids) {
            line(out, label, named(id, name));
        }
    }

    private static String named(String id, Function<String, String> name) {
        try {
            String resolved = name.apply(id);
            return resolved == null || resolved.isBlank() ? id : resolved + " [" + id + "]";
        } catch (RuntimeException exception) {
            return id + "（名称不可用）";
        }
    }

}
