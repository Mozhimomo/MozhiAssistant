package com.mozhi.assistant.bootstrap.ui;

/** 完成提示可以已读；未恢复的执行异常持续提示，打开交流不视为解决问题。 */
final class FleetNotice {
    enum Badge { NONE, SUCCESS, ATTENTION }
    private String acknowledged = "";
    private FleetPresentation current = FleetPresentation.from(java.util.Map.of());
    void update(FleetPresentation next, boolean conversationOpen) { current = next; if (conversationOpen) acknowledge(); }
    void acknowledge() { acknowledged = current.eventKey(); }
    void reset() { acknowledged = ""; current = FleetPresentation.from(java.util.Map.of()); }
    Badge badge() {
        if (current.attention()) return Badge.ATTENTION;
        if (acknowledged.equals(current.eventKey())) return Badge.NONE;
        return current.success() ? Badge.SUCCESS : Badge.NONE;
    }
}
