package com.mozhi.fleet.config;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 只控制执行检查的频率和重规划次数，不包含舰队经营策略。 */
public record FleetSupervisionConfig(double reviewIntervalDays, double minReviewSeconds, double stallDays, int maxReplans) {
    public static FleetSupervisionConfig load(String url) throws Exception {
        Properties p=new Properties();
        try(var reader=new InputStreamReader(URI.create(url).toURL().openStream(),StandardCharsets.UTF_8)) {p.load(reader);}
        return new FleetSupervisionConfig(number(p,"fleetReviewIntervalDays",1,0.1,100),
                number(p,"fleetReviewMinIntervalSeconds",30,1,3600),
                number(p,"fleetStallDays",3,0.5,100),(int)number(p,"fleetMaxReplans",3,1,20));
    }
    private static double number(Properties p,String key,double fallback,double min,double max) {
        String text=p.getProperty(key,"").strip();
        double value=text.isEmpty()?fallback:Double.parseDouble(text);
        if(!Double.isFinite(value) || value<min || value>max || (key.equals("fleetMaxReplans") && value!=Math.floor(value)))
            throw new IllegalArgumentException(key+" 数值无效");
        return value;
    }
}
