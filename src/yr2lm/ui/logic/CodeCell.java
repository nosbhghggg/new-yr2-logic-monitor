package yr2lm.ui.logic;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import yr2lm.Yr2Vars;
import yr2lm.util.LogicHighlight;
import yr2lm.util.ScriptModel;

import java.util.List;

/**
 * 独立解耦的逻辑代码行组件。
 * <p>
 * 核心修复:
 * 1. 移除在 `Table` 自身调用 `.left().padLeft(...)` 的非法 API 调用 (移至 `CodePanel.table.add(c).padLeft(...)` Cell 上)，修复编译错误；
 * 2. 粘贴代码片段按钮 (`Icon.downSmall`) 增加 `try/catch` 异常防护与 `Vars.ui.showException` / `showErrorMessage` 错误提示，
 *    彻底防止剪贴板中存在非法 mlog 语法（如未闭合引号、外部未定义标签或普通聊天文本）时点击粘贴直接崩溃游戏。
 */
public class CodeCell extends Table {
    public final ScriptModel.Row row;
    public int line;
    private final CodePanel panel;
    private String hlSrc, hlText;
    private Label breakpointTag;
    private final InputListener tapListener;
    private long lastTapTime = 0L;

    @SuppressWarnings("this-escape")
    public CodeCell(CodePanel panel, ScriptModel.Row row) {
        super();
        this.panel = panel;
        this.row = row;
        touchable = Touchable.enabled;
        final CodeCell self = this;

        tapListener = new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (event.isTouchFocusCancel()) return;
                Element t = event.targetActor;
                if (!CodePanel.isRowTap(t, self, breakpointTag)) return;

                long now = Time.millis();
                CodeCell prev = panel.activeEditCell;
                if (now - lastTapTime < 320L) {
                    lastTapTime = 0L;
                    panel.setActiveRow(self, true);
                    if (prev != null && prev != self) prev.codeCellBuild();
                    codeCellBuild();
                    return;
                }
                lastTapTime = now;

                panel.setActiveRow(prev == self ? null : self, false);
                if (prev != null && prev != self) prev.codeCellBuild();
                codeCellBuild();
            }
        };
        codeCellBuild();
    }

    boolean editable() {
        return panel.activeEditCell == this && panel.activeRowEditing;
    }

    public void codeCellBuild() {
        clear();
        left();
        breakpointTag = null;
        boolean showTrace = panel.isTraceShown();
        ScriptModel model = panel.model;
        DebugExecutor executor = panel.getExecutor();

        breakpointTag = label(() -> {
            int currentPc = executor.getRunningPc(model.size());
            if (currentPc == line) {
                return model.isBreakpoint(line) ? ">[red]>" : ">>";
            }
            return model.isBreakpoint(line) ? "[red]>" : "";
        }).width(CodePanel.BP_COL_W).growY().padRight(CodePanel.CELL_PAD).get();
        breakpointTag.setAlignment(Align.right);
        breakpointTag.clicked(() -> model.toggleBreakpoint(line));

        if (editable()) {
            TextField field = field(row.text, s -> row.text = s).minWidth(0f).grow().padRight(CodePanel.CELL_PAD).get();
            field.keyDown(KeyCode.enter, () -> {
                if (line + 1 < panel.codeCells.size()) {
                    CodeCell next = panel.codeCells.get(line + 1);
                    panel.setActiveRow(next, true);
                    codeCellBuild();
                    next.codeCellBuild();
                } else {
                    panel.insertRows(line + 1, List.of(ScriptModel.blankRow()));
                }
            });
            field.keyDown(KeyCode.escape, () -> {
                panel.activeRowEditing = false;
                codeCellBuild();
            });
            if (Core.scene != null) {
                Core.scene.setKeyboardFocus(field);
                field.setCursorPosition(row.text.length());
            }
        } else {
            Label lb = new Label(this::highlightCache);
            lb.setWrap(true);
            lb.setAlignment(Align.left);
            add(lb).minWidth(0f).grow().padRight(CodePanel.CELL_PAD);
        }

        if (!showTrace && panel.activeEditCell == this) {
            button(Icon.pencilSmall, Styles.emptyi, () -> {
                panel.activeRowEditing = !panel.activeRowEditing;
                codeCellBuild();
            }).size(CodePanel.EDIT_BTN).right();

            button(Icon.addSmall, Styles.emptyi, () -> panel.insertRows(line + 1, List.of(ScriptModel.blankRow()))).size(CodePanel.EDIT_BTN).right();

            button(Icon.downSmall, Styles.emptyi, () -> {
                try {
                    List<ScriptModel.Row> rows = ScriptModel.parseSnippet(
                            Core.app.getClipboardText(),
                            line + 1,
                            panel.getLogicBuild().block.privileged
                    );
                    panel.insertRows(line + 1, rows);
                } catch (RuntimeException e) {
                    if (Vars.ui != null) {
                        Vars.ui.showErrorMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                    }
                }
            }).size(CodePanel.EDIT_BTN).right();

            button(Icon.refreshSmall, Styles.emptyi, () -> {
                row.text = row.origin;
                codeCellBuild();
            }).size(CodePanel.EDIT_BTN).right();

            button(Icon.cancelSmall, Styles.emptyi, () -> panel.removeRow(line)).size(CodePanel.EDIT_BTN).right();
        }

        add(new Label(row.unsaved() ? "[#" + Yr2Vars.dataChangeHex + "]+" : "[#" + Yr2Vars.mutedHex + "]" + line))
                .width(CodePanel.LINE_NUMBER_W).right().padRight(CodePanel.CELL_PAD);

        addListener(tapListener);
    }

    @Override
    public void draw() {
        boolean showTrace = panel.isTraceShown();
        TraceTimeline timeline = panel.getTimeline();
        DebugExecutor executor = panel.getExecutor();

        if (showTrace && timeline.isHighlighted(line)) {
            Draw.color(Yr2Vars.themeColor, 0.28f);
            Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
            Lines.stroke(2f, Yr2Vars.themeColor);
            Lines.rect(x + 1f, y + 1f, width - 2f, height - 2f);
            Draw.reset();
        } else if (executor.isPaused() && executor.getCounter() == line) {
            Draw.color(Yr2Vars.themeColor, 0.90f);
            Fill.rect(x + 2f, y + height / 2f, 4f, height - 2f);
            Draw.color(Yr2Vars.themeColor, 0.16f);
            Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
            Draw.reset();
        } else if (executor.isPaused()) {
            float heat = executor.lineHeat(line, true);
            if (heat > 0.01f) {
                Draw.color(Yr2Vars.themeColor, heat * 0.75f);
                Fill.rect(x + 2f, y + height / 2f, 3f, height - 2f);
                Draw.color(Yr2Vars.themeColor, heat * 0.10f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                Draw.reset();
            }
        }

        super.draw();

        TraceTimeline.Record jp = panel.currentJumpRecord();
        if (jp != null) drawJumpLink(jp.fromLine, jp.toLine);
    }

    private void drawJumpLink(int from, int to) {
        float gx = x + ColumnLayout.scene(CodePanel.ROW_PAD_L + CodePanel.BP_COL_W * 0.5f);
        float xr = x + ColumnLayout.scene(CodePanel.ROW_PAD_L + CodePanel.BP_COL_W + 1f);
        float cy = y + height / 2f;
        Draw.color(Yr2Vars.jumpColor);
        Lines.stroke(1.6f, Yr2Vars.jumpColor);
        if (from == to) {
            Fill.circle(gx, cy, 3f);
            Draw.reset();
            return;
        }
        int lo = Math.min(from, to), hi = Math.max(from, to);
        boolean targetBelow = to > from;
        if (line > lo && line < hi) {
            Lines.line(gx, y + 1f, gx, y + height - 1f);
        } else if (line == from) {
            Lines.line(gx, cy, xr, cy);
            Lines.line(gx, cy, gx, targetBelow ? y + 1f : y + height - 1f);
            Fill.circle(xr, cy, 2.4f);
        } else if (line == to) {
            Lines.line(gx, cy, gx, targetBelow ? y + height - 1f : y + 1f);
            Lines.line(gx, cy, xr, cy);
            float s = 4f;
            Fill.tri(xr + s, cy, xr - s, cy + s, xr - s, cy - s);
        }
        Draw.reset();
    }

    private String highlightCache() {
        if (hlText == null || !hlSrc.equals(row.text)) {
            hlSrc = row.text;
            hlText = LogicHighlight.highlight(row.text);
        }
        return hlText;
    }
}
