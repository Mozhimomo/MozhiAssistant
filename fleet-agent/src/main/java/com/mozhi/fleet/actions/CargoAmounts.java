package com.mozhi.fleet.actions;

import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.campaign.fleet.CargoData;

/** CargoAPI.getQuantity 只读货堆；原生玩家货舱另存商品的小数累计量，可能为负数。 */
public final class CargoAmounts {
    private CargoAmounts() {}

    public static float quantity(CargoAPI cargo, CargoAPI.CargoItemType type, Object data) {
        float visible = cargo.getQuantity(type, data);
        if (type == CargoAPI.CargoItemType.RESOURCES && cargo instanceof CargoData nativeCargo)
            return visible + nativeCargo.getPartial(type.name() + data);
        return visible;
    }
}
