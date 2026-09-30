package com.mozhi.assistant.runtime.tools;

import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import static com.mozhi.assistant.runtime.tools.SpecText.*;

/** 各类别独立读取详情；仅访问基础规格，不创建舰船、货物或物品插件实例。 */
public final class SpecDetails {
    private SpecDetails() {
    }

    public static String readWeapon(WeaponSpecAPI spec) {
        StringBuilder out = new StringBuilder();
        section(out, "基本资料", text -> {
            source(text, spec);
            line(text, "制造商", spec.getManufacturer());
            line(text, "描述", description(spec.getWeaponId(), Description.Type.WEAPON));
            line(text, "武器类型", spec.getType());
            line(text, "安装槽类型", spec.getMountType());
            line(text, "尺寸", spec.getSize());
            line(text, "伤害类型", spec.getDamageType());
            line(text, "光束武器", spec.isBeam());
            line(text, "装配点 OP", spec.getOrdnancePointCost(null));
            line(text, "基础价值/星币", spec.getBaseValue());
            line(text, "等级", spec.getTier());
            line(text, "标签", spec.getTags());
        });
        section(out, "火力与幅能（基础值）", text -> {
            line(text, "最大射程", spec.getMaxRange());
            var stats = spec.getDerivedStats();
            if (stats == null) {
                line(text, "派生火力数据", "未提供");
                return;
            }
            line(text, "单次伤害", stats.getDamagePerShot());
            line(text, "单次 EMP", stats.getEmpPerShot());
            line(text, "每秒伤害 DPS", stats.getDps());
            line(text, "持续 DPS", stats.getSustainedDps());
            line(text, "每秒 EMP", stats.getEmpPerSecond());
            line(text, "连发总伤害", stats.getBurstDamage());
            line(text, "每秒幅能", stats.getFluxPerSecond());
            line(text, "持续每秒幅能", stats.getSustainedFluxPerSecond());
            line(text, "每点伤害幅能", stats.getFluxPerDam());
        });
        section(out, "弹药与射击", text -> {
            line(text, "使用弹药", spec.usesAmmo());
            if (spec.usesAmmo()) {
                line(text, "弹药上限", spec.getMaxAmmo());
                line(text, "每秒恢复弹药", spec.getAmmoPerSecond());
                line(text, "单次装填数量", spec.getReloadSize());
            }
            line(text, "连发数量", spec.getBurstSize());
            line(text, "连发持续时间/秒", spec.getBurstDuration());
            line(text, "蓄力时间/秒", spec.getChargeTime());
            line(text, "转向速度/度每秒", spec.getTurnRate());
            line(text, "最小/最大散布角", number(spec.getMinSpread()) + " / " + number(spec.getMaxSpread()));
            line(text, "主要用途", spec.getPrimaryRoleStr());
            line(text, "弹速说明", spec.getSpeedStr());
            line(text, "跟踪说明", spec.getTrackingStr());
            line(text, "精度说明", spec.getAccuracyStr());
            line(text, "特殊效果", spec.getCustomPrimary());
            line(text, "其他效果", spec.getCustomAncillary());
        });
        return out.toString();
    }

    public static String readHullMod(HullModSpecAPI spec) {
        StringBuilder out = new StringBuilder();
        section(out, "基本资料", text -> {
            source(text, spec);
            line(text, "制造商", spec.getManufacturer());
            line(text, "基础价值/星币", spec.getBaseValue());
            line(text, "等级", spec.getTier());
            line(text, "默认解锁", spec.isAlwaysUnlocked());
            line(text, "隐藏", spec.isHidden());
            line(text, "完全隐藏", spec.isHiddenEverywhere());
            line(text, "标签", spec.getTags());
            line(text, "界面标签", spec.getUITags());
            line(text, "说明", "按船体尺寸列出基础描述；安装条件、动态提示与实际效果可能由插件脚本决定。");
        });
        hullModForSize(out, spec, HullSize.FRIGATE, "护卫舰");
        hullModForSize(out, spec, HullSize.DESTROYER, "驱逐舰");
        hullModForSize(out, spec, HullSize.CRUISER, "巡洋舰");
        hullModForSize(out, spec, HullSize.CAPITAL_SHIP, "主力舰");
        return out.toString();
    }

    private static void hullModForSize(StringBuilder out, HullModSpecAPI spec, HullSize size, String label) {
        section(out, label, text -> {
            line(text, "装配点 OP", spec.getCostFor(size));
            line(text, "效果", spec.getDescription(size));
            if (spec.getSModEffectFormat() != null && !spec.getSModEffectFormat().isBlank()) {
                line(text, "S 插效果", spec.getSModDescription(size));
            }
        });
    }

    public static String readShipSystem(ShipSystemSpecAPI spec) {
        StringBuilder out = new StringBuilder();
        section(out, "系统资料", text -> {
            source(text, spec);
            line(text, "系统 ID", spec.getId());
            line(text, "名称", spec.getName());
            line(text, "描述", description(spec.getId(), Description.Type.SHIP_SYSTEM));
            line(text, "开关型系统", spec.isToggle());
            line(text, "相位系统", spec.isPhaseCloak());
            line(text, "标签", spec.getTags());
        });
        section(out, "时序与充能（基础值）", text -> {
            line(text, "启动/持续/关闭时间（秒）",
                    number(spec.getIn()) + " / " + number(spec.getActive()) + " / " + number(spec.getOut()));
            line(text, "冷却时间/秒", spec.getCooldown(null));
            line(text, "使用充能", spec.usesAmmo());
            if (spec.usesAmmo()) {
                int uses = spec.getMaxUses(null);
                line(text, "充能上限", uses == Integer.MAX_VALUE ? "无限" : uses);
                line(text, "充能恢复时间/秒", spec.getRegen(null));
            }
        });
        section(out, "消耗与使用限制（基础值）", text -> {
            line(text, "每次使用固定幅能", spec.getFluxPerUse());
            line(text, "每秒固定幅能", spec.getFluxPerSecond());
            line(text, "每次使用幅能系数（耗散/容量）",
                    number(spec.getFluxPerUseBaseRate()) + " / " + number(spec.getFluxPerUseBaseCap()));
            line(text, "每秒幅能系数（耗散/容量）",
                    number(spec.getFluxPerSecondBaseRate()) + " / " + number(spec.getFluxPerSecondBaseCap()));
            line(text, "每次使用战备消耗（规格值）", spec.getCrPerUse());
            line(text, "产生硬幅能", spec.generatesHardFlux());
            line(text, "允许开火", spec.isFiringAllowed());
            line(text, "允许护盾", spec.isShieldAllowed());
            line(text, "允许主动排幅", spec.isVentingAllowed());
            line(text, "允许幅能耗散", spec.isDissipationAllowed());
        });
        return out.toString();
    }

    public static String readCommodity(CommoditySpecAPI spec) {
        StringBuilder out = new StringBuilder();
        section(out, "普通商品资料", text -> {
            source(text, spec);
            line(text, "描述", description(spec.getId(), Description.Type.RESOURCE));
            line(text, "来源说明", spec.getOrigin());
            line(text, "基础价格/星币", spec.getBasePrice());
            line(text, "每单位货舱占用", spec.getCargoSpace());
            line(text, "堆叠大小", spec.getStackSize());
            line(text, "人员类", spec.isPersonnel());
            line(text, "燃料类", spec.isFuel());
            line(text, "补给类", spec.isSupplies());
            line(text, "非经济商品", spec.isNonEcon());
            line(text, "需求类别", spec.getDemandClass());
            line(text, "经济单位（规格值）", spec.getEconUnit());
            line(text, "经济等级", spec.getEconomyTier());
            line(text, "标签", spec.getTags());
        });
        return out.toString();
    }

    public static String readSpecialItem(SpecialItemSpecAPI spec) {
        StringBuilder out = new StringBuilder();
        section(out, "特殊物品资料", text -> {
            source(text, spec);
            line(text, "制造商", spec.getManufacturer());
            line(text, "描述", spec.getDesc());
            line(text, "基础价格/星币", spec.getBasePrice());
            line(text, "每单位货舱占用", spec.getCargoSpace());
            line(text, "堆叠大小", spec.getStackSize());
            line(text, "稀有度（规格值）", spec.getRarity());
            line(text, "规格参数", spec.getParams());
            line(text, "标签", spec.getTags());
            line(text, "说明", "这是物品类型规格；蓝图等物品的实例内容、动态名称与价格取决于实例数据。");
        });
        return out.toString();
    }
}
