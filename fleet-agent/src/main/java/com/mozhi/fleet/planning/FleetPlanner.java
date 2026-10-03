package com.mozhi.fleet.planning;

import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.llm.LlmClient;
import com.mozhi.llm.LlmConfig;

/** 子智能体后台规划和目标检查，不执行任何游戏操作。 */
public final class FleetPlanner {
    private final String configUrl;
    private FleetPlanningService service;
    private FleetGoalReviewService reviewer;
    public FleetPlanner(String configUrl) { this.configUrl = configUrl; }

    /** 使用自建模型进行离线验证。 */
    public FleetPlanner(LlmClient client) {
        this.configUrl = null;
        this.service = client.aiService(FleetPlanningService.class);
        this.reviewer = client.aiService(FleetGoalReviewService.class);
    }

    public FleetPlan plan(String observations) throws Exception {
        initialize();
        return service.plan(observations).toPlan();
    }
    public FleetGoalReview review(String observations) throws Exception {
        initialize();
        FleetGoalReview review=reviewer.review(observations); review.validate(); return review;
    }
    private void initialize() throws Exception {
        if (service == null) {
            // 规划和检查同样需要最终正文；不能缩减输出预算或丢掉配置的思考参数。
            LlmClient client = LlmClient.create(LlmConfig.load(configUrl));
            service = client.aiService(FleetPlanningService.class);
            reviewer = client.aiService(FleetGoalReviewService.class);
        }
    }
}
