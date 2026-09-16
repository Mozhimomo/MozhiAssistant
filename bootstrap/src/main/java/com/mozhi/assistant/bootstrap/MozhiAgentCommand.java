package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** Registered in data/console/commands.csv. Network calls remain on AgentSession's worker. */
public final class MozhiAgentCommand implements BaseCommand {
    private static final String DEFAULT_PROMPT =
            "请调用工具读取舰队详情，逐舰列出型号、舰长、战备、武器和插件，再计算 17 + 25，并用中文回答。";

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign() || context.isInCombat()) {
            Console.showMessage("请在战役地图中使用 MozhiAgent。");
            return CommandResult.WRONG_CONTEXT;
        }
        String[] parts = args.trim().split("\\s+", 2);
        String action = parts[0];
        if (action.isEmpty() || "test".equalsIgnoreCase(action)) {
            return runTest(parts.length == 2 ? parts[1] : DEFAULT_PROMPT);
        }
        if (parts.length != 1) return CommandResult.BAD_SYNTAX;
        if ("chat".equalsIgnoreCase(action)) {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
                return CommandResult.WRONG_CONTEXT;
            }
            if (Global.getSector().getCampaignUI().isShowingDialog()
                    || Global.getSector().getCampaignUI().getCurrentCoreTab() != null) {
                Console.showMessage("Close other dialogs and core screens before opening Mozhi chat.");
                return CommandResult.WRONG_CONTEXT;
            }
            Console.showDialogOnClose(new ChatDialog(), Global.getSector().getPlayerFleet());
            Console.showMessage("Close the console to open Mozhi chat.");
            return CommandResult.SUCCESS;
        }
        if ("status".equalsIgnoreCase(action)) {
            AgentSession session = AgentSession.current();
            session.poll();
            Console.showMessage(session.resultText());
            return CommandResult.SUCCESS;
        }
        if ("reset".equalsIgnoreCase(action)) {
            AgentSession.reset();
            Console.showMessage("[MozhiAgent] 会话已重置；下次 test 时重新读取配置。");
            return CommandResult.SUCCESS;
        }
        return CommandResult.BAD_SYNTAX;
    }

    /** Entry point for custom manual checks. Must be invoked on the campaign main thread. */
    public static CommandResult runTest(String message) {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            Console.showMessage("请先载入战役存档。");
            return CommandResult.WRONG_CONTEXT;
        }
        if (message == null || message.isBlank()) return CommandResult.BAD_SYNTAX;
        AgentSession session = AgentSession.current();
        if (session.poll()) Console.showMessage(session.resultText());
        if (session.busy()) {
            Console.showMessage("已有请求进行中。使用 MozhiAgent status 查看，或 MozhiAgent reset 取消。");
            return CommandResult.ERROR;
        }
        session.send(message);
        Console.showMessage("[MozhiAgent] 已提交测试。工具实时读取的舰队详情将发送给配置的模型服务。");
        Console.showMessage("等待时请关闭控制台返回战役，或使用 MozhiAgent status 推进并查看结果。");
        Console.showMessage("结果将自动输出到控制台；也可输入 MozhiAgent status 查询。");
        return CommandResult.SUCCESS;
    }
}
