package com.mozhi.fleet.trading;

import java.util.HashMap;
import java.util.Map;
import static com.mozhi.fleet.trading.TradeSnapshot.*;

/** 整批报价边界；生产实现只允许游戏主线程调用，测试可以提供确定性价格。 */
@FunctionalInterface
public interface TradeQuotes {
    double quote(Market market, Quote item, int quantity, boolean buy);

    static TradeQuotes tables() {
        return (market, item, quantity, buy) -> (buy ? item.buys() : item.sells()).getOrDefault(quantity, -1d);
    }

    /** 缓存仅属于本次计算；到站重算时新建，防止继续使用旧行情。 */
    final class Cached implements TradeQuotes {
        private record Key(String market, String channel, String item, int quantity, boolean buy) {}
        private final TradeQuotes source;
        private final Map<Key, Double> values = new HashMap<>();
        public Cached(TradeQuotes source) { this.source = source; }
        public double quote(Market market, Quote item, int quantity, boolean buy) {
            if (quantity <= 0 || buy && !item.canBuy() || !buy && !item.canSell()) return -1;
            var key = new Key(market.id(), item.submarketId(), item.commodityId(), quantity, buy);
            return values.computeIfAbsent(key, ignored -> {
                double price = source.quote(market, item, quantity, buy);
                return Double.isFinite(price) && price >= 0 ? price : -1d;
            });
        }
        public int calls() { return values.size(); }
    }
}
