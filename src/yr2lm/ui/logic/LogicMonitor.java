package yr2lm.ui.logic;

import arc.math.geom.Vec2;
import arc.scene.ui.layout.Cell;
import arc.util.Log;
import arc.util.Time;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Monitor;
import yr2lm.ui.window.Splitter;

/**
 * 逻辑块调试器宿主窗口。
 */
public class LogicMonitor extends Monitor {
    private static final float TOOLS_H = 80f;

    private final LogicBlock.LogicBuild logicBuild;

    private DebugExecutor executor;
    private final TraceTimeline timeline;
    private final ColumnLayout layout;

    private VarPanel varPanel;
    private CodePanel codePanel;
    private final TracePanel tracePanel;
    private final Splitter varCodeSplitter;
    private final Splitter codeTraceSplitter;

    private boolean showVarPage = true, showEditPage = false;
    private boolean showTrace = false;

    private Cell<?> varToolsCellRef, varPanelCellRef, editToolsCellRef, editPanelCellRef, tracePanelCellRef;
    private final LayoutPass pass = new LayoutPass();

    private static final class LayoutPass {
        float varW, codeW, traceW, codeToolsW;
        boolean layoutDirty, debugDirty, applyingWidths;
    }

    private void requestLayout() {
        pass.layoutDirty = true;
    }

    private void requestDebugRefresh() {
        pass.debugDirty = true;
    }

    private boolean traceShown() {
        return showTrace && showEditPage;
    }

    private void computeWidths() {
        float total = ColumnLayout.design(size.x);
        ColumnLayout.Widths w = layout.allocate(total, showVarPage, showEditPage, showTrace);
        pass.varW = w.varW;
        pass.codeW = w.codeW;
        pass.traceW = w.traceW;
        pass.codeToolsW = traceShown() ? pass.codeW + ColumnLayout.SPLIT_W + pass.traceW : pass.codeW;
    }

    private void ensureWindowFitsColumns() {
        float reqSceneW = ColumnLayout.scene(layout.requiredWidth(showVarPage, showEditPage, showTrace));
        minSize.x = reqSceneW;
        if (size.x < reqSceneW) {
            size.x = reqSceneW;
        }
    }

    @SuppressWarnings("this-escape")
    public LogicMonitor(String text, LogicBlock.LogicBuild logicBuild, Vec2 pos, float initW, float initH) {
        super(text, logicBuild, pos);
        this.logicBuild = logicBuild;
        size.set(initW, initH);
        minSize.y = 240f;

        this.timeline = new TraceTimeline();
        this.layout = new ColumnLayout("yr2lm.split." + logicBuild.tile.x + ":" + logicBuild.tile.y);

        this.executor = new DebugExecutor(
                logicBuild,
                timeline,
                () -> this.codePanel != null ? this.codePanel.getCodeCount() : 0,
                line -> this.codePanel != null ? this.codePanel.getCodeAt(line) : "",
                line -> this.codePanel != null && this.codePanel.isBreakpoint(line),
                () -> this.varPanel != null ? this.varPanel.captureSnapshot(this.executor.getCounter()) : new arc.struct.ObjectMap<>()
        );

        this.varPanel = new VarPanel(
                logicBuild,
                executor,
                timeline,
                () -> {
                    if (logicBuild.executor.counter == null) return;
                    logicBuild.executor.counter.numval = 0;
                    executor.setCounter(0);
                    if (executor.isPaused()) scrollToCounter();
                },
                () -> {
                    executor.resetSession();
                    varPanel.rebuild();
                    codePanel.rebuild();
                    requestDebugRefresh();
                    requestLayout();
                },
                () -> {
                    if (!showEditPage) {
                        showEditPage = true;
                        float comfortW = ColumnLayout.scene(760f);
                        if (size.x < comfortW) size.x = Math.min(comfortW, Yr2Vars.sceneW() - 20f);
                    } else {
                        showVarPage = false;
                    }
                    ensureWindowFitsColumns();
                    requestLayout();
                }
        );

        this.codePanel = new CodePanel(
                logicBuild,
                executor,
                timeline,
                () -> showTrace,
                () -> {
                    if (!showVarPage) {
                        showVarPage = true;
                        float comfortW = ColumnLayout.scene(760f);
                        if (size.x < comfortW) size.x = Math.min(comfortW, Yr2Vars.sceneW() - 20f);
                    } else {
                        showEditPage = false;
                    }
                    ensureWindowFitsColumns();
                    requestLayout();
                },
                () -> {
                    showTrace = !showTrace;
                    if (!showTrace) {
                        timeline.restoreRealtime();
                    } else {
                        float comfortW = ColumnLayout.scene(showVarPage ? 920f : 680f);
                        if (size.x < comfortW) size.x = Math.min(comfortW, Yr2Vars.sceneW() - 20f);
                    }
                    ensureWindowFitsColumns();
                    codePanel.setActiveRow(null, false);
                    codePanel.refreshCellsUI();
                    requestDebugRefresh();
                    requestLayout();
                },
                () -> {
                    if (!executor.isPaused()) logicPause();
                    stepOnce();
                },
                () -> {
                    if (!executor.isPaused()) logicPause();
                    skipToBreakpoint();
                },
                () -> {
                    if (executor.isPaused()) logicRerun();
                    else logicPause();
                },
                this::exitRollback,
                this::requestLayout
        );

        this.tracePanel = new TracePanel(timeline, line -> codePanel.scrollToLine(line));

        this.tracePanel.setListeners(
            rec -> {
                if (timeline.getSelected() == rec) {
                    timeline.restoreRealtime();
                } else {
                    timeline.rollbackTo(rec);
                }
                requestDebugRefresh();
                requestLayout();
                if (timeline.getHighlightedLine() >= 0) {
                    Time.run(2f, () -> codePanel.scrollToLine(timeline.getHighlightedLine()));
                }
            },
            () -> {
                timeline.clear();
                requestDebugRefresh();
                requestLayout();
            }
        );

        this.varCodeSplitter = new Splitter(
            dx -> {
                layout.dragVar(ColumnLayout.design(dx), ColumnLayout.design(size.x), showVarPage, showEditPage, showTrace);
                applyLiveWidths();
            },
            () -> {
                layout.resetVar();
                requestLayout();
            },
            () -> {
                layout.saveVarRatio();
                requestLayout();
            }
        );

        this.codeTraceSplitter = new Splitter(
            dx -> {
                layout.dragCode(ColumnLayout.design(dx), ColumnLayout.design(size.x), showVarPage, showTrace);
                applyLiveWidths();
            },
            () -> {
                layout.resetCode();
                requestLayout();
            },
            () -> {
                layout.saveCodeRatio();
                requestLayout();
            }
        );

        init();

        monitorTable.update(() -> {
            if (pass.debugDirty) {
                pass.debugDirty = false;
                refreshDebugViews();
            }
            if (pass.layoutDirty) {
                pass.layoutDirty = false;
                monitorTableBuild();
            }
            if (logicBuild.executor.counter == null) return;
            executor.tickMaintenance();
            varPanel.syncVars();
            codePanel.syncExternalCodeChange();
        });
    }

    @Override
    public void init() {
        varPanel.rebuild();
        codePanel.rebuild();
        tracePanel.rebuild();
        monitorTableBuild();
    }

    private void refreshDebugViews() {
        varPanel.syncVars();
        tracePanel.rebuild();
    }

    @Override
    public void removeFromScene() {
        executor.setPaused(false, codePanel.getCodeCount());
        super.removeFromScene();
    }

    private void applyLiveWidths() {
        computeWidths();
        if (varToolsCellRef != null) varToolsCellRef.width(pass.varW);
        if (varPanelCellRef != null) varPanelCellRef.width(pass.varW);
        if (editToolsCellRef != null) editToolsCellRef.width(pass.codeToolsW);
        if (editPanelCellRef != null) editPanelCellRef.width(pass.codeW);
        if (tracePanelCellRef != null) tracePanelCellRef.width(pass.traceW);
        monitorTable.invalidateHierarchy();
    }

    private void monitorTableBuild() {
        ensureWindowFitsColumns();
        computeWidths();
        final boolean trace = traceShown();

        monitorTable.clear();
        monitorTable.top().left();
        varToolsCellRef = varPanelCellRef = null;
        editToolsCellRef = editPanelCellRef = tracePanelCellRef = null;

        if (showVarPage) varToolsCellRef = monitorTable.add(varPanel.tools).height(TOOLS_H).minWidth(0f).width(pass.varW);
        if (showEditPage) {
            if (showVarPage) monitorTable.add().width(ColumnLayout.SPLIT_W);
            float toolsW = pass.codeToolsW;
            editToolsCellRef = monitorTable.add(codePanel.editTools).height(TOOLS_H).minWidth(0f).width(toolsW);
            if (trace) editToolsCellRef.colspan(3);
        }
        monitorTable.row();

        if (showVarPage) varPanelCellRef = monitorTable.add(varPanel.scroll).minWidth(0f).minHeight(0f).width(pass.varW).growY();
        if (showEditPage) {
            if (showVarPage) monitorTable.add(varCodeSplitter).width(ColumnLayout.SPLIT_W).growY();
            editPanelCellRef = monitorTable.add(codePanel.scroll).minWidth(0f).minHeight(0f).width(pass.codeW).growY();
        }
        if (trace) {
            monitorTable.add(codeTraceSplitter).width(ColumnLayout.SPLIT_W).growY();
            tracePanelCellRef = monitorTable.add(tracePanel.tracePane).minWidth(0f).minHeight(0f).width(pass.traceW).growY();
        }
    }

    private void stepOnce() {
        executor.stepOnce();
        scrollToCounter();
        requestDebugRefresh();
    }

    private void skipToBreakpoint() {
        executor.skipToBreakpoint();
        scrollToCounter();
        requestDebugRefresh();
    }

    private void scrollToCounter() {
        codePanel.scrollToLine(executor.getCounter());
    }

    private void logicPause() {
        executor.setPaused(true, codePanel.getCodeCount());
        scrollToCounter();
        requestDebugRefresh();
    }

    private void logicRerun() {
        timeline.restoreRealtime();
        executor.clearHeat();
        executor.setPaused(false, codePanel.getCodeCount());
        requestDebugRefresh();
    }

    private void exitRollback() {
        timeline.restoreRealtime();
        requestDebugRefresh();
        requestLayout();
    }

    @Override
    protected void onResized() {
        super.onResized();
        if (pass.applyingWidths) return;
        pass.applyingWidths = true;
        try {
            applyLiveWidths();
        } catch (Throwable e) {
            Log.err("yr2lm applyLiveWidths failed: " + e, e);
            pass.layoutDirty = true;
        } finally {
            pass.applyingWidths = false;
        }
    }

    @Override
    public LogicBlock.LogicBuild getBuilding() {
        return logicBuild;
    }
}
