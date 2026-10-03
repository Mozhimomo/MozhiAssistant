package com.mozhi.fleet.model;

/** 主线程采集的后勤数值；零消耗表示无需该航行资源。 */
public record FleetResources(double fuel, double fuelCapacity, double fuelPerLightYear,
                             double supplies, double suppliesPerDay, double crew, double minimumCrew) {
    public FleetResources {
        for (double value : new double[]{fuel, fuelCapacity, fuelPerLightYear, supplies, suppliesPerDay, crew, minimumCrew})
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("舰队后勤数据必须为有限非负数");
    }
}
