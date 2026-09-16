package com.mozhi.assistant.bootstrap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import org.lazywizard.console.Console;

/** Native campaign text/option UI, with a text-only custom input panel. No ship/GL rendering. */
public final class ChatDialog implements InteractionDialogPlugin {
    private static final String SEND = "mozhi-send";
    private static final String RESET = "mozhi-reset";
    private static final String CLOSE = "mozhi-close";
    private InteractionDialogAPI dialog;
    private TextFieldAPI input;
    private LabelAPI status;
    private AgentSession displayedSession;
    private long displayedMessageId;
    private boolean closed;

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        dialog.setPromptText("与墨汁对话");
        float width = Math.min(400, Global.getSettings().getScreenWidth() * 0.35f);
        CustomPanelAPI panel = dialog.getVisualPanel().showCustomPanel(width, 260, null);
        TooltipMakerAPI form = panel.createUIElement(width, 260, false);
        form.addPara("墨汁 · 舰载智能体", 0);
        form.addPara("输入消息后点击下方“发送”。支持粘贴，最多 2000 个字符。", 12);
        input = form.addTextField(width - 16, 96, Fonts.DEFAULT_SMALL, 12);
        input.setMaxChars(2000);
        input.setLimitByStringWidth(false);
        input.setHandleCtrlV(true);
        input.setUndoOnEscape(false);
        input.setText(AgentSession.current().draft());
        status = form.addPara("", 12);
        form.addPara("输入和工具实时读取的舰队详情会提交给你配置的模型服务。", 12);
        panel.addUIElement(form).inTL(0, 0);
        dialog.getOptionPanel().addOption("发送", SEND);
        dialog.getOptionPanel().addOption("新对话 / 重读配置", RESET);
        dialog.getOptionPanel().addOption("关闭（保留本次会话）", CLOSE);
        dialog.setOptionOnEscape("关闭（保留本次会话）", CLOSE);
        synchronizeDisplay();
        input.grabFocus();
    }

    @Override
    public void advance(float amount) {
        if (closed || dialog == null) return;
        AgentSession session = AgentSession.current();
        // Dialogs pause campaign time. Poll here too so replies appear while this UI is open.
        if (session.poll() && session.reportsToConsole()) Console.showMessage(session.resultText());
        synchronizeDisplay();
        AgentSession.current().setDraft(input.getText());
        dialog.getOptionPanel().setEnabled(SEND, !session.busy() && !input.getText().isBlank());
    }

    private void synchronizeDisplay() {
        AgentSession session = AgentSession.current();
        if (session != displayedSession) {
            displayedSession = session;
            displayedMessageId = 0;
            dialog.getTextPanel().clear();
            dialog.getTextPanel().addParagraph("墨汁通讯频道", new Color(120, 210, 230));
            dialog.getTextPanel().addParagraph("舰长，想聊些什么？关闭窗口后可继续本次对话；新对话会清空记录与记忆。");
            input.setText(session.draft());
        }
        for (AgentSession.ChatMessage message : session.history()) {
            if (message.id() <= displayedMessageId) continue;
            Color color = "舰长".equals(message.speaker()) ? new Color(240, 200, 110)
                    : "墨汁".equals(message.speaker()) ? new Color(120, 210, 230) : new Color(240, 140, 140);
            dialog.getTextPanel().addParagraph(message.speaker(), color);
            // Non-formatting paragraph API preserves %, newlines and arbitrary model text.
            dialog.getTextPanel().addParagraph(message.text());
            displayedMessageId = message.id();
        }
        status.setText(session.status());
        dialog.getOptionPanel().setOptionText(session.busy() ? "等待回复……" : "发送", SEND);
        dialog.getOptionPanel().setEnabled(SEND, !session.busy() && !input.getText().isBlank());
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (SEND.equals(optionData)) {
            AgentSession session = AgentSession.current();
            String message = input.getText().trim();
            if (session.busy() || message.isEmpty()) return;
            if (session.send(message, false)) {
                input.setText("");
                session.setDraft("");
            }
            synchronizeDisplay();
            input.grabFocus();
        } else if (RESET.equals(optionData)) {
            AgentSession.reset();
            synchronizeDisplay();
            input.grabFocus();
        } else if (CLOSE.equals(optionData)) {
            AgentSession.current().setDraft(input.getText());
            closed = true;
            dialog.dismiss();
        }
    }

    @Override public void optionMousedOver(String text, Object data) { }
    @Override public void backFromEngagement(EngagementResultAPI result) { }
    @Override public Object getContext() { return null; }
    @Override public Map<String, MemoryAPI> getMemoryMap() { return Collections.emptyMap(); }
}
