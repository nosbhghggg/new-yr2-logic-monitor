package yr2lm.ui.logic;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.FocusListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.logic.LVar;
import mindustry.logic.LogicDialog;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;
import yr2lm.util.LogicHighlight;
import yr2lm.util.MemUtil;

/**
 * 独立解耦的变量表行组件。
 * <p>
 * 核心修复 (彻底消除变量全是 `...` 的 Bug):
 * 1. 还原第一代变量名的 `name.setWrap(true)` 与 `name.setAlignment(Align.left, Align.top)`，
 *    绝对不对 `nameCell` 施加 `.minWidth(0f)`，因为 Arc 的 `Label` 在 `ellipsis = true` 时 `getMinWidth()` 等于未截断全文宽，
 *    一旦与 `.minWidth(0f)` 混用会导致首帧宽度塌缩为 0 从而把变量全变成 `...`！
 * 2. 移除在 `Table` 自身调用 `.left().pad(...)` 的非法 API 调用 (移至 `VarPanel.table.add(cell).pad(0, 8, 0, 8)` Cell 上)，修复编译错误。
 */
public class VarCell extends Table {
    private static final Color typeTmp = new Color();

    public LVar var;
    public final String rawName;
    private final String escapedRawName;
    private final VarPanel panel;
    private final InputListener tapListener;

    private Label valLabel;
    private Cell<Label> valCell;
    private float lastFitW = -1f;
    private int refitDelay = 0;
    private float appliedValH;
    private boolean wasExpanded;
    private long lastTapTime = 0L;

    private String cachedNameStr = null;
    private boolean lastTypeIsObj = false;
    private Class<?> lastObjClass = null;

    private String cachedValStr = null;
    private boolean valCacheValid = false;
    private boolean lastValIsObj = false;
    private long lastNumBits = 0L;
    private Object lastObjRef = null;
    private long lastUnitFlagBits = 0L;
    private int lastCounterForNaN = -1;
    private TraceTimeline.Record lastSnapshotRec = null;

    @SuppressWarnings("this-escape")
    public VarCell(VarPanel panel, LVar varInit, String varName) {
        super();
        this.panel = panel;
        this.var = varInit;
        this.rawName = varName;
        this.escapedRawName = varName.indexOf('[') >= 0 ? varName.replace("[", "[[") : varName;
        touchable = Touchable.enabled;

        tapListener = new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (event.isTouchFocusCancel()) return;
                boolean tap = CodePanel.isRowTap(event.targetActor, VarCell.this, null);
                if (!tap) return;

                long now = Time.millis();
                if (now - lastTapTime < 320L && (!var.constant || var.name.equals("@counter"))) {
                    lastTapTime = 0L;
                    panel.setEditingVarName(rawName);
                    rebuildCell();
                    return;
                }
                lastTapTime = now;

                if (panel.isAllExpanded()) return;
                panel.toggleManualExpanded(rawName);
                rebuildCell();
            }
        };

        update(() -> {
            fitExpandedHeight();
            if (!var.isobj || var.objval instanceof String) return;
            Element e = Yr2Vars.hoverElement();
            boolean rowHovered = (e != null && (e == this || e.isDescendantOf(this)));
            if (panel.isDrawAllVars() || rowHovered) {
                if (var.objval instanceof Unit unit)
                    DrawExt.info(arc.util.Tmp.v1.set(panel.getLogicBuild().x, panel.getLogicBuild().y), unit, var.name, Yr2Vars.themeColor);
                else if (var.objval instanceof Building b)
                    DrawExt.info(arc.util.Tmp.v1.set(panel.getLogicBuild().x, panel.getLogicBuild().y), b, var.name, Yr2Vars.themeColor);
            }
            if (rowHovered) {
                if (var.objval instanceof Unit unit)
                    DrawExt.screenWorldLine(arc.util.Tmp.v2.set(Core.input.mouse()), unit, Yr2Vars.themeColor);
                else if (var.objval instanceof Building b)
                    DrawExt.screenWorldLine(arc.util.Tmp.v2.set(Core.input.mouse()), b, Yr2Vars.themeColor);
            }
        });

        rebuildCell();
    }

    public void invalidateValueCache() {
        valCacheValid = false;
        cachedNameStr = null;
    }

    private boolean isExpanded() {
        return panel.isAllExpanded() || panel.isManualExpanded(rawName);
    }

    private String currentValText() {
        TraceTimeline timeline = panel.getTimeline();
        TraceTimeline.Record snapRec = timeline.getSelected();
        if (snapRec != null) {
            if (valCacheValid && lastSnapshotRec == snapRec) return cachedValStr;
            lastSnapshotRec = snapRec;
            valCacheValid = true;
            String snapVal = timeline.getSnapshotValue(var.name);
            if (snapVal != null) {
                cachedValStr = timeline.isVarChanged(var.name)
                        ? "[#" + Yr2Vars.dataChangeHex + "]" + snapVal + " [diff]"
                        : snapVal;
                return cachedValStr;
            }
        }

        boolean isObj = var.isobj;
        long numBits = Double.doubleToRawLongBits(var.numval);
        Object objRef = var.objval;
        long unitFlagBits = (isObj && objRef instanceof Unit u) ? Double.doubleToRawLongBits(u.flag) : 0L;
        int curCounter = (Double.isNaN(var.numval) && var.name.equals("@counter")) ? panel.getExecutor().getCounter() : -1;

        if (valCacheValid
                && lastSnapshotRec == null
                && lastValIsObj == isObj
                && (isObj ? (lastObjRef == objRef && lastUnitFlagBits == unitFlagBits)
                          : (lastNumBits == numBits && lastCounterForNaN == curCounter))) {
            return cachedValStr;
        }

        lastSnapshotRec = null;
        lastValIsObj = isObj;
        lastNumBits = numBits;
        lastObjRef = objRef;
        lastUnitFlagBits = unitFlagBits;
        lastCounterForNaN = curCounter;
        cachedValStr = panel.formatVarText(var, panel.getExecutor().getCounter());
        valCacheValid = true;
        return cachedValStr;
    }

    public void rebuildCell() {
        clear();
        left();

        // 100% 还原第一代变量名布局: setWrap(true) + 固定 width(dynNameCol)，绝不出现首帧塌缩成 "..."
        Label name = new Label(this::nameText);
        name.setWrap(true);
        name.setAlignment(Align.left, Align.top);
        add(name).left().top().width(panel.getDynNameCol()).growY().padRight(8f);

        if (rawName.equals(panel.getEditingVarName())) {
            String initVal = var.isobj ? (var.objval instanceof String s ? "\"" + s + "\"" : String.valueOf(var.objval)) : MemUtil.doubleToString(var.numval);
            TextField field = field(initVal, s -> {}).minWidth(0f).growX().get();
            Runnable commit = () -> {
                if (rawName.equals(panel.getEditingVarName())) {
                    VarPanel.applyVarValue(var, field.getText());
                    if (var.name.equals("@counter")) {
                        panel.getExecutor().setCounter((int) var.numval);
                    }
                    panel.setEditingVarName(null);
                    invalidateValueCache();
                    rebuildCell();
                }
            };
            field.keyDown(KeyCode.enter, commit);
            field.keyDown(KeyCode.escape, () -> {
                panel.setEditingVarName(null);
                rebuildCell();
            });
            field.addListener(new FocusListener() {
                @Override
                public void keyboardFocusChanged(FocusEvent event, Element actor, boolean focused) {
                    if (!focused) commit.run();
                }
            });
            if (Core.scene != null) Core.scene.setKeyboardFocus(field);
            addListener(tapListener);
            return;
        }

        boolean expanded = isExpanded();
        if (expanded) {
            valLabel = new Label(this::currentValText);
            valLabel.setWrap(true);
            valLabel.setAlignment(Align.left, Align.top);
            valCell = add(valLabel).left().top().minWidth(0f).growX().growY();
            if (!wasExpanded) { appliedValH = -1f; lastFitW = -1f; refitDelay = 0; }
            wasExpanded = true;
            fitExpandedHeight();
        } else {
            valLabel = null;
            valCell = null;
            appliedValH = 0f;
            lastFitW = -1f;
            refitDelay = 0;
            wasExpanded = false;
            Label value = new Label(this::currentValText);
            value.setEllipsis(true);
            value.setAlignment(Align.left);
            add(value).left().minWidth(0f).growX().growY();
        }

        addListener(tapListener);
    }

    private void fitExpandedHeight() {
        if (valLabel == null || valCell == null) return;
        float w = valLabel.getWidth();
        if (w < 60f) return;
        float want = Math.max(32f, ColumnLayout.design(valLabel.getPrefHeight()));

        if (appliedValH < 0f) {
            appliedValH = want;
            lastFitW = w;
            refitDelay = 0;
        } else if (Math.abs(w - lastFitW) > 0.5f) {
            lastFitW = w;
            refitDelay = 3;
            appliedValH = Math.max(appliedValH, want);
        } else if (refitDelay > 0) {
            appliedValH = Math.max(appliedValH, want);
            if (--refitDelay == 0) appliedValH = want;
        } else {
            appliedValH = Math.max(appliedValH, want);
        }
        valCell.minHeight(appliedValH);
    }

    private String nameText() {
        Class<?> curClass = (var.isobj && var.objval != null) ? var.objval.getClass() : null;
        if (cachedNameStr != null && lastTypeIsObj == var.isobj && lastObjClass == curClass) {
            return cachedNameStr;
        }
        lastTypeIsObj = var.isobj;
        lastObjClass = curClass;
        Color bright = LogicHighlight.brighten(LogicDialog.typeColor(var, typeTmp));
        cachedNameStr = "[#" + bright.toString().substring(0, 6) + "]" + escapedRawName + "[]";
        return cachedNameStr;
    }
}
