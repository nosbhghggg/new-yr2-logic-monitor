package yr2lm.ui.logic;

import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Yrailiuxa2;
import yr2lm.util.MemUtil;
import yr2lm.util.ScriptModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑块代码编辑与断点视图面板。
 * <p>
 * 核心修复:
 * 1. 恢复原版区分度极高的调试图标: `Icon.left` (\ue802) 表示单步执行，`Icon.undo` (\ue835) 表示跳至下一断点，不再出现两个右箭头重复的问题；
 * 2. 暂停按钮直接通过 `pauseButton.getStyle().imageUp` 实时切换 `Icon.play` / `Icon.pause`；
 * 3. 在 `table.add(c).minHeight(35f).minWidth(0f).growX().padLeft(ROW_PAD_L)` Cell 上设置左边距。
 */
public class CodePanel {
    static final float BP_COL_W = 30f;
    static final float LINE_NUMBER_W = 35f;
    static final float CELL_PAD = 5f;
    static final float ROW_PAD_L = 10f;
    static final float EDIT_BTN = 28f;

    public final ScriptModel model = new ScriptModel();
    public final Table editTools;
    public final Table table;
    public final ScrollPane scroll;
    public final ArrayList<CodeCell> codeCells = new ArrayList<>();

    private final LogicBlock.LogicBuild logicBuild;
    private final DebugExecutor executor;
    private final TraceTimeline timeline;
    private final arc.func.Boolp isTraceShown;
    private final Runnable onToggleVarPage;
    private final Runnable onToggleTracePanel;
    private final Runnable onStepOnce;
    private final Runnable onSkipToBreakpoint;
    private final Runnable onPauseToggle;
    private final Runnable onExitRollback;
    private final Runnable onRequestLayout;

    CodeCell activeEditCell = null;
    boolean activeRowEditing;
    ScriptModel.Row pendingFocus = null;
    private String lastLoadedBlockCode = null;

    @SuppressWarnings("this-escape")
    public CodePanel(LogicBlock.LogicBuild logicBuild,
                     DebugExecutor executor,
                     TraceTimeline timeline,
                     arc.func.Boolp isTraceShown,
                     Runnable onToggleVarPage,
                     Runnable onToggleTracePanel,
                     Runnable onStepOnce,
                     Runnable onSkipToBreakpoint,
                     Runnable onPauseToggle,
                     Runnable onExitRollback,
                     Runnable onRequestLayout) {
        this.logicBuild = logicBuild;
        this.executor = executor;
        this.timeline = timeline;
        this.isTraceShown = isTraceShown;
        this.onToggleVarPage = onToggleVarPage;
        this.onToggleTracePanel = onToggleTracePanel;
        this.onStepOnce = onStepOnce;
        this.onSkipToBreakpoint = onSkipToBreakpoint;
        this.onPauseToggle = onPauseToggle;
        this.onExitRollback = onExitRollback;
        this.onRequestLayout = onRequestLayout;

        editTools = new Table();
        table = new Table();
        table.top().left();
        scroll = new ScrollPane(table);
        Yrailiuxa2.configurePane(scroll);
        scroll.update(() -> Yr2Vars.bindScrollFocus(scroll));
        buildTools();
        rebuild();
    }

    public LogicBlock.LogicBuild getLogicBuild() {
        return logicBuild;
    }

    public DebugExecutor getExecutor() {
        return executor;
    }

    public TraceTimeline getTimeline() {
        return timeline;
    }

    public boolean isTraceShown() {
        return isTraceShown.get();
    }

    public void setActiveRow(CodeCell cell, boolean editing) {
        activeEditCell = cell;
        activeRowEditing = cell != null && editing;
    }

    public static boolean isRowTap(Element target, Element row, Element breakpointTag) {
        if (target == null || !target.isDescendantOf(row)) return false;
        if (target == breakpointTag) return false;
        for (Element e = target; e != row; e = e.parent) {
            if (e instanceof Button || e instanceof TextField) return false;
        }
        return true;
    }

    public int getCodeCount() {
        return model.size();
    }

    public String getCodeAt(int index) {
        return model.textAt(index);
    }

    public boolean isBreakpoint(int line) {
        return model.isBreakpoint(line);
    }

    public boolean hasUnsavedChanges() {
        for (int i = 0; i < model.size(); i++) {
            ScriptModel.Row r = model.get(i);
            if (r.unsaved() && !r.text.isEmpty()) return true;
        }
        return false;
    }

    public void syncExternalCodeChange() {
        String currentCode = logicBuild.code;
        if (currentCode != null && !currentCode.equals(lastLoadedBlockCode)) {
            if (!activeRowEditing && !hasUnsavedChanges()) {
                rebuild();
            }
        }
    }

    TraceTimeline.Record currentJumpRecord() {
        TraceTimeline.Record r;
        if (timeline.isSnapshotActive()) {
            r = timeline.getSelected();
        } else if (executor.isPaused()) {
            ArrayList<TraceTimeline.Record> h = timeline.getHistory();
            r = h.isEmpty() ? null : h.get(h.size() - 1);
        } else {
            return null;
        }
        return (r != null && r.isJump) ? r : null;
    }

    public void scrollToLine(int line) {
        int count = codeCells.size();
        if (count == 0 || scroll == null) return;
        int clamped = Mathf.clamp(line, 0, count - 1);
        table.validate();
        scroll.validate();
        CodeCell target = codeCells.get(clamped);
        float maxY = scroll.getMaxY();
        if (maxY <= 0.5f) return;
        float distanceFromTop = table.getHeight() - (target.y + target.getHeight() * 0.5f);
        float desiredScrollY = distanceFromTop - scroll.getHeight() * 0.5f;
        scroll.setScrollY(Mathf.clamp(desiredScrollY, 0f, maxY));
    }

    private void buildTools() {
        editTools.clear();
        editTools.table(t -> {
            t.button(Icon.refresh, Styles.emptyi, () -> {
                if (timeline.isSnapshotActive()) {
                    if (onExitRollback != null) onExitRollback.run();
                } else {
                    rebuild();
                    if (onRequestLayout != null) onRequestLayout.run();
                }
            }).minWidth(0f).grow();

            t.button(Icon.save, Styles.emptyi, this::uploadCode).minWidth(0f).grow();

            t.button(Icon.tree, Styles.emptyi, () -> {
                if (onToggleVarPage != null) onToggleVarPage.run();
            }).minWidth(0f).grow();
        }).height(40).minWidth(0f).growX();

        editTools.row();
        editTools.table(t -> {
            ImageButton pauseButton = t.button(Icon.pause, Styles.emptyi, () -> {
                if (onPauseToggle != null) onPauseToggle.run();
            }).minWidth(0f).grow().get();
            pauseButton.update(() -> pauseButton.getStyle().imageUp = executor.isPaused() ? Icon.play : Icon.pause);

            // 单步执行 (Icon.left \ue802) 与 运行至下一断点 (Icon.undo \ue835)，恢复原版经典区分度，彻底消除图标重复
            t.button(Icon.left, Styles.emptyi, () -> {
                if (onStepOnce != null) onStepOnce.run();
            }).minWidth(0f).grow();

            t.button(Icon.undo, Styles.emptyi, () -> {
                if (onSkipToBreakpoint != null) onSkipToBreakpoint.run();
            }).minWidth(0f).grow();

            t.button(Icon.list, Styles.emptyi, () -> {
                if (onToggleTracePanel != null) onToggleTracePanel.run();
            }).minWidth(0f).grow();
        }).height(40).minWidth(0f).growX();
    }

    public void rebuild() {
        setActiveRow(null, false);
        lastLoadedBlockCode = logicBuild.code;
        model.loadFrom(logicBuild.code, logicBuild.block.privileged);
        if (model.size() == 0) {
            model.insert(0, List.of(ScriptModel.blankRow()));
        }
        syncCells();
    }

    public void refreshCellsUI() {
        codeCells.forEach(CodeCell::codeCellBuild);
    }

    private void syncCells() {
        ObjectMap<ScriptModel.Row, CodeCell> pool = new ObjectMap<>();
        for (CodeCell c : codeCells) pool.put(c.row, c);
        codeCells.clear();
        for (int i = 0; i < model.size(); i++) {
            ScriptModel.Row row = model.get(i);
            CodeCell cell = pool.get(row);
            if (cell == null) cell = new CodeCell(this, row);
            cell.line = i;
            codeCells.add(cell);
        }
        if (activeEditCell != null && !codeCells.contains(activeEditCell)) setActiveRow(null, false);
        if (pendingFocus != null) {
            CodeCell focus = null;
            for (CodeCell c : codeCells) {
                if (c.row == pendingFocus) focus = c;
            }
            pendingFocus = null;
            if (focus != null) setActiveRow(focus, true);
        }

        table.clearChildren();
        table.top().left();
        for (CodeCell c : codeCells) {
            table.add(c).minHeight(35f).minWidth(0f).growX().padLeft(ROW_PAD_L);
            table.row();
        }
        codeCells.forEach(CodeCell::codeCellBuild);
    }

    public void insertRows(int at, List<ScriptModel.Row> incoming) {
        if (incoming.isEmpty()) return;
        int index = Math.max(0, Math.min(at, model.size()));
        model.insert(index, incoming);
        pendingFocus = model.get(index);
        syncCells();
    }

    public void removeRow(int at) {
        if (model.remove(at) == null) return;
        if (model.size() == 0) {
            model.insert(0, List.of(ScriptModel.blankRow()));
        }
        syncCells();
    }

    public void uploadCode() {
        if (!MemUtil.commitLogicCode(logicBuild, model.toCode())) {
            return;
        }
        if (executor.isPaused()) {
            executor.setPaused(true, getCodeCount());
            executor.setCounter(0);
        }
        timeline.clear();
        executor.clearHeat();
        rebuild();
        if (onRequestLayout != null) onRequestLayout.run();
    }
}
