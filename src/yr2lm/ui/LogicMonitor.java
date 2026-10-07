package yr2lm.ui;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import arc.util.Log;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LVar;
import mindustry.logic.LogicDialog;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;
import yr2lm.util.LogicHighlight;
import yr2lm.util.MemUtil;
import yr2lm.util.ScriptModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逻辑块调试器面板。
 * <p>
 * 架构重构 (1.7.0):
 * 提供变量监视、代码就地编辑、单步与断点调试、时序流水回溯、自由分栏排版。
 */
public class LogicMonitor extends Monitor {
    private static final Color typeTmp = new Color();
    private static final Pattern CONST_NAME = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");

    private final LogicBlock.LogicBuild logicBuild;

    // 领域状态与排版策略
    private final DebugExecutor executor;
    private final TraceTimeline timeline;
    private final ColumnLayout layout;

    // 视图面板与分割线
    private final VarPanel varPanel;
    private final CodePanel codePanel;
    private final TracePanel tracePanel;
    private final Splitter varCodeSplitter;
    private final Splitter codeTraceSplitter;

    private boolean showVarPage = true, showEditPage = false;
    private boolean autoCollapsedDual = false;
    private boolean showTrace = false;
    private final boolean showLightTrace = true;

    /** 每帧只问一次"鼠标此刻压在哪个元素上", 全部变量行共用; 旧写法每行各问一次 = 每帧几十次命中查询。 */
    private Element frameHover;

    private Cell<?> varToolsCellRef, varPanelCellRef, editToolsCellRef, editPanelCellRef, tracePanelCellRef;

    /**
     * 本轮排版的协调状态: 集中收着"这一轮里各栏各定多宽""要不要重排/重取调试视图""改列宽防重入"这几个散标记,
     * 免得它们摊在宿主顶层像一堆零散变量。硬宽度只有分栏算式能给, 任何地方都不许再自己算第二套;
     * 刷新总闸只举一手, 真正动手的只有帧末那一处, 同一帧内多次举手合并成一次。
     */
    private final LayoutPass pass = new LayoutPass();

    private void requestLayout() {
        pass.layoutDirty = true;
    }

    private void requestDebugRefresh() {
        pass.debugDirty = true;
    }

    /** 一轮排版的产物与刷新举手: 各栏硬宽度(设计单位)、两个待办脏标记、改列宽防重入闸。 */
    private static final class LayoutPass {
        float varW, codeW, traceW, codeToolsW;
        boolean layoutDirty, debugDirty, applyingWidths;
    }

    private static final float TOOLS_H = 80f;

    /** 双栏并排的最低窗口宽(设计单位): 窄过这里自动收起变量页, 恢复要涨到 +60 以上. 这是可读性问题, 不是裁剪问题. */
    private static final float DUAL_MIN_WIDTH = 700f;
    private static final float DUAL_RESTORE_WIDTH = DUAL_MIN_WIDTH + 60f;

    /** 流水栏只有和代码栏同时出现时才占列, 与 ColumnLayout 内部同一个判据。 */
    private boolean traceShown() {
        return showTrace && showEditPage;
    }

    /**
     * 唯一的自报出口: 各栏的下限与愿望, 全部现问对应面板。
     * 算式那边不许留任何控件尺寸, 面板这边改一排控件就同时改了它自己的报数。
     */
    private ColumnLayout.Report reportNow() {
        boolean trace = traceShown();
        return new ColumnLayout.Report(
                varPanel.floorWidth(), codePanel.floorWidth(!trace), tracePanel.floorWidth(),
                varPanel.getDesiredWidth(), codePanel.getDesiredWidth(), tracePanel.getDesiredWidth());
    }

    /** 唯一的取宽度入口: 每次要用的时候现问算式, 拿到的是同一份结果(设计单位, 直接交给格子)。 */
    private void computeWidths() {
        float total = ColumnLayout.design(size.x);
        layout.handleResize(total);
        ColumnLayout.Report r = reportNow();
        ColumnLayout.Widths w = layout.allocate(total, showVarPage, showEditPage, showTrace, r);
        pass.varW = w.varW;
        pass.codeW = w.codeW;
        pass.traceW = w.traceW;
        //代码工具条横跨到流水栏上方时, 它的宽度就是被跨的那几列之和
        pass.codeToolsW = traceShown() ? pass.codeW + ColumnLayout.SPLIT_W + pass.traceW : pass.codeW;
    }

    //region 1. 变量域 (Variable Column): 变量监视、搜索过滤与实体引线

    private class VarPanel {
        final Table tools;
        final Table table;
        final ScrollPane scroll;
        final ArrayList<LVar> constants = new ArrayList<>();
        final ArrayList<LVar> links = new ArrayList<>();
        /** 本轮要渲染的行: 变量对象与显示名一一对应, 由 collectRows 填好, buildRows 只照着摆控件。 */
        final ArrayList<LVar> rowVars = new ArrayList<>();
        final ArrayList<String> rowNames = new ArrayList<>();
        final ArrayList<VarCell> rowCells = new ArrayList<>();
        /** textBuffer 行插在第几行之前; -1 表示被过滤掉了。 */
        int textBufferAt = -1;
        /** 上一次结构重建时的变量数组引用: 换引用才意味着重新编译过。 */
        private LVar[] watchedVars;
        String varFilter = "";
        boolean filterCc = false, filterW = false;
        boolean drawAllVars = false;
        float dynNameCol = 130f;
        int maxValContentLen = 4;
        final ObjectMap<String, Boolean> manualExpandedVars = new ObjectMap<>();
        boolean allExpanded = false;

        //region 本栏自报的宽度: 下面每个数都是 buildTools 里真实摆下去的控件, 改那几行就同时改这里
        /** 搜索框的最小可输入宽. */
        static final float SEARCH_MIN_W = 60f;
        /** 顶栏那三个见方按钮的边长. */
        static final float TOOL_BTN = 36f;
        static final int TOOL_BTN_COUNT = 3;
        /** 顶栏最左边的内边距. */
        static final float TOOLS_PAD_L = 6f;
        /** 值列最宽只贡献到这里, 免得某个长小数把整栏占满. */
        static final float VAL_CAP_W = 240f;
        //endregion

        /**
         * 变量栏绝不能被裁的是那一排工具: 手写的合计与引擎现量的工具表取大者。
         * 现量这一步顺手兜住"只写了自动伸展、没声明尺寸的按键"——它们的自然宽不会出现在手写的数里。
         */
        float floorWidth() {
            return Math.max(SEARCH_MIN_W + TOOL_BTN * TOOL_BTN_COUNT + TOOLS_PAD_L,
                    ColumnLayout.design(tools.getMinWidth()));
        }

        VarPanel() {
            tools = new Table();
            table = new Table();
            table.top().left();
            scroll = new ScrollPane(table);
            Yrailiuxa2.configurePane(scroll);
            scroll.update(() -> Yrailiuxa2.bindScrollFocus(scroll));
            buildTools();
            rebuild();
        }

        public float getDesiredWidth() {
            return dynNameCol + Math.min(VAL_CAP_W, maxValContentLen * ColumnLayout.CHAR_W + 20f) + 24f;
        }

        public ObjectMap<String, String> captureSnapshot(int counter) {
            ObjectMap<String, String> snapshot = new ObjectMap<>();
            LVar[] vars = logicBuild.executor.vars;
            if (vars != null) {
                for (LVar v : vars) snapshot.put(v.name, formatVarText(v, counter));
            }
            for (LVar v : constants) snapshot.put(v.name, formatVarText(v, counter));
            return snapshot;
        }

        private void buildTools() {
            tools.clear();
            tools.table(t -> {
                t.button(Icon.rotate, Styles.emptyi, () -> {
                    if (logicBuild.executor.counter == null) return;
                    logicBuild.executor.counter.numval = 0;
                    executor.setCounter(0);
                    if (executor.isPaused()) scrollToCounter();
                }).grow();

                t.button(Icon.trash, Styles.emptyi, () -> {
                    executor.resetSession();
                    rebuild();
                    codePanel.rebuild();
                    requestDebugRefresh();
                    requestLayout();
                }).grow();

                ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
                ImageButton drawButton = t.button(Icon.eyeOffSmall, Styles.emptyi, () -> {}).grow().get();
                drawButton.clicked(() -> {
                    drawAllVars = !drawAllVars;
                    style.imageUp = drawAllVars ? Icon.eyeSmall : Icon.eyeOffSmall;
                    drawButton.setStyle(style);
                });

                t.button(Icon.edit, Styles.emptyi, () -> {
                    autoCollapsedDual = false;
                    if (showEditPage) showVarPage = false;
                    else {
                        showEditPage = true;
                        if (ColumnLayout.design(size.x) < DUAL_MIN_WIDTH) showVarPage = false;
                    }
                    requestLayout();
                }).grow();
            }).height(40).growX();

            tools.row();
            tools.table(t -> {
                t.field(varFilter, s -> {
                    varFilter = s;
                    rebuild();
                }).minWidth(SEARCH_MIN_W).padLeft(TOOLS_PAD_L).growX();

                TextButton buttonCc = t.button(filterCc ? "Cc" : "[grey]Cc", Styles.cleart, () -> {}).size(TOOL_BTN).get();
                buttonCc.clicked(() -> {
                    filterCc = !filterCc;
                    buttonCc.setText(filterCc ? "Cc" : "[grey]Cc");
                    rebuild();
                });

                TextButton buttonW = t.button(filterW ? "W" : "[grey]W", Styles.cleart, () -> {}).size(TOOL_BTN).get();
                buttonW.clicked(() -> {
                    filterW = !filterW;
                    buttonW.setText(filterW ? "W" : "[grey]W");
                    rebuild();
                });

                ImageButton.ImageButtonStyle expandStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
                ImageButton expandButton = t.button(allExpanded ? Icon.downSmall : Icon.upSmall, expandStyle, () -> {}).size(TOOL_BTN).get();
                expandButton.clicked(() -> {
                    allExpanded = !allExpanded;
                    //箭头转到"全部收起"时必须连手动展开一起清空, 否则单行永远收不回去
                    if (!allExpanded) manualExpandedVars.clear();
                    expandStyle.imageUp = allExpanded ? Icon.downSmall : Icon.upSmall;
                    expandButton.setStyle(expandStyle);
                    rebuild();
                });
            }).height(40).growX();
        }

        void rebuild() {
            collectRows();
            buildRows();
        }

        /** 每帧轮询的唯一入口: 处理器换过变量数组(=重新编译)才动结构, 其余时候什么都不做。 */
        void syncVars() {
            LVar[] vars = logicBuild.executor.vars;
            if (vars == null || vars == watchedVars) return;
            rebuild();
        }

        /**
         * 纯数据阶段: 先定下有哪些行、每行显示什么名字, 并把名字列宽与值列宽一次算完。
         * 列宽必须先于建控件算好, 否则行名用的是上一轮的数字(这是原实现的错位 bug)。
         */
        private void collectRows() {
            constants.clear();
            links.clear();
            rowVars.clear();
            rowNames.clear();
            textBufferAt = -1;
            LExecutor exec = logicBuild.executor;
            watchedVars = exec.vars;
            for (LVar var : exec.vars) {
                if (!var.constant) continue;
                if (var.name.startsWith("@")) constants.add(var);
                else if (!var.name.startsWith("___")) links.add(var);
            }
            collectBuiltinConstants(exec);

            int maxNameLen = 4;
            int maxValLen = 4;
            int currentCounter = executor.getCounter();
            for (LVar var : exec.vars) {
                if (var.constant) continue;
                if (!checkVarName(var.name)) continue;
                maxNameLen = Math.max(maxNameLen, var.name.length());
                String s = formatVarText(var, currentCounter);
                if (s != null) maxValLen = Math.max(maxValLen, s.length());
                rowVars.add(var);
                rowNames.add(var.name);
            }
            for (LVar var : constants) {
                if (!checkVarName(var.name)) continue;
                maxNameLen = Math.max(maxNameLen, var.name.length());
                String s = formatVarText(var, currentCounter);
                if (s != null) maxValLen = Math.max(maxValLen, s.length());
                rowVars.add(var);
                rowNames.add(var.name);
            }
            if (checkVarName("textBuffer")) {
                maxValLen = Math.max(maxValLen, Math.min(30, exec.textBuffer.length()));
                textBufferAt = rowVars.size();
            }
            for (int i = 0, size = links.size(); i < size; i++) {
                LVar var = links.get(i);
                maxNameLen = Math.max(maxNameLen, var.name.length() + 3);
                String s = formatVarText(var, currentCounter);
                if (s != null) maxValLen = Math.max(maxValLen, s.length());
                rowVars.add(var);
                rowNames.add(String.format("[%d]%s", i, var.name));
            }

            dynNameCol = Mathf.clamp(maxNameLen * ColumnLayout.CHAR_W + 16f, 130f, 190f);
            maxValContentLen = maxValLen;
        }

        /** 结构阶段: 按显示名复用已有的行对象, 只重排表格骨架。 */
        private void buildRows() {
            ObjectMap<String, VarCell> pool = new ObjectMap<>();
            for (VarCell cell : rowCells) pool.put(cell.rawName, cell);
            rowCells.clear();

            table.clear();
            table.top().left();
            for (int i = 0; i < rowVars.size(); i++) {
                if (i == textBufferAt) addTextBufferRow();
                String name = rowNames.get(i);
                LVar var = rowVars.get(i);
                VarCell cell = pool.get(name);
                if (cell == null) {
                    cell = new VarCell(var, name);
                } else {
                    cell.var = var;
                    cell.rebuildCell();
                }
                rowCells.add(cell);
                table.add(cell).growX();
                table.row();
            }
            if (textBufferAt >= rowVars.size()) addTextBufferRow();
        }

        private void addTextBufferRow() {
            table.table(t -> {
                Label nameLbl = new Label("textBuffer");
                nameLbl.setEllipsis(true);
                nameLbl.setAlignment(Align.left);
                t.add(nameLbl).left().width(dynNameCol).growY().padRight(8f);

                Label valLbl = new Label(() -> {
                    int len = logicBuild.executor.textBuffer.length();
                    String preview = logicBuild.executor.textBuffer.toString().replace("\n", " ");
                    return String.format("%d | \"%s\"", len, preview);
                });
                valLbl.setEllipsis(true);
                valLbl.setAlignment(Align.left);
                t.add(valLbl).left().minWidth(0f).growX().growY();

                t.clicked(() -> Core.app.setClipboardText(logicBuild.executor.textBuffer.toString()));
            }).height(32f).growX().pad(0, 8, 0, 8);
            table.row();
        }

        private void collectBuiltinConstants(LExecutor exec) {
            addConstant(exec.unit);
            addConstant(exec.thisv);
            addConstant(exec.ipt);
            addConstant(exec.queryResult);
            boolean privileged = logicBuild.block.privileged;
            Matcher matcher = CONST_NAME.matcher(logicBuild.code == null ? "" : logicBuild.code);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            while (matcher.find()) names.add(matcher.group());
            for (String name : names) {
                if (hasVar(exec, name)) continue;
                LVar var = Vars.logicVars.get(name, privileged);
                addConstant(var);
            }
        }

        private void addConstant(LVar var) {
            if (var == null) return;
            if (hasVar(logicBuild.executor, var.name)) return;
            for (LVar other : constants) if (other.name.equals(var.name)) return;
            constants.add(var);
        }

        private boolean hasVar(LExecutor exec, String name) {
            for (LVar var : exec.vars) if (var.name.equals(name)) return true;
            return false;
        }

        private boolean checkVarName(String name) {
            int index;
            if (filterCc) index = name.indexOf(varFilter);
            else index = name.toUpperCase().indexOf(varFilter.toUpperCase());
            if (index < 0) return false;
            if (filterW) {
                boolean filter = true;
                if (index > 0) filter = !Character.isAlphabetic(name.charAt(index - 1));
                if (index + varFilter.length() < name.length())
                    filter &= !Character.isAlphabetic(name.charAt(index + varFilter.length()));
                return filter;
            }
            return true;
        }

        private class VarCell extends Table {
            public LVar var;
            private final String rawName;
            private InputListener tapListener;
            private Label valLabel;
            private Cell<Label> valCell;
            /** 上一次给这一行落高时它量到的实际列宽(引擎像素): 判定"是不是真的换了宽度"用, 据此决定要不要重新跟随内容重排. */
            private float lastFitW = -1f;
            /** 宽度正在变时的落定倒计时: >0 表示刚改过宽度, 观察期先维持旧高(只涨不塌), 归零那帧才按新宽度干净重排一次, 消除拖动中途"塌一下又弹回"的名字上闪. */
            private int refitDelay = 0;
            /** 本次展开期间已钉住的行高(设计单位): 一次展开内只增不减, 是消除抖动的关键. */
            private float appliedValH;
            /** 上一帧这行是否处于展开态: 只有真正"收起→展开"这一次才把钉住的行高复位, 单纯重建/展开全部不动它. */
            private boolean wasExpanded;

            public VarCell(LVar varInit, String varName) {
                super();
                this.var = varInit;
                this.rawName = varName;
                touchable = Touchable.enabled;

                //行点击是开关: 收起的展开、展开的收起; 全局"全部展开"生效期间不作数, 那时收起只归箭头管
                tapListener = new InputListener() {
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        return true;
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        //拖动列表超过 20 像素时, 框架会取消本行的点击焦点, 并补发一次松手事件(坐标是无效值): 认这个信号就够, 不必自己量位移
                        if (event.isTouchFocusCancel()) return;
                        boolean tap = CodePanel.isRowTap(event.targetActor, VarCell.this, null);
                        if (!tap) return;
                        if (allExpanded) return;
                        if (manualExpanded()) manualExpandedVars.remove(rawName);
                        else manualExpandedVars.put(rawName, true);
                        rebuildCell();
                    }
                };
                rebuildCell();
            }

            private boolean manualExpanded() {
                return manualExpandedVars.get(rawName, false);
            }

            private boolean isExpanded() {
                return allExpanded || manualExpanded();
            }

            // 回溯激活时优先显示快照值与差分标记
            private String currentValText() {
                if (timeline.isSnapshotActive()) {
                    String snapVal = timeline.getSnapshotValue(var.name);
                    if (snapVal != null) {
                        if (timeline.isVarChanged(var.name)) {
                            return "[#" + Yr2Vars.dataChangeHex + "]" + snapVal + " [diff]";
                        }
                        return snapVal;
                    }
                }
                return formatVarText(this.var, executor.getCounter());
            }

            public void rebuildCell() {
                clear();
                table(t -> {
                    Label name = new Label(this::nameText);
                    name.setWrap(true);
                    name.setAlignment(Align.left, Align.top);
                    t.add(name).left().top().width(dynNameCol).growY().padRight(8f);

                    boolean expanded = isExpanded();
                    if (expanded) {
                        //展开态同样挂轮询: 换成快照字符串会让整列数值永久静止 (箭头按下就不刷新的真因)
                        valLabel = new Label(this::currentValText);
                        valLabel.setWrap(true);
                        valLabel.setAlignment(Align.left, Align.top);
                        valCell = t.add(valLabel).left().top().minWidth(0f).growX().growY();
                        //只有真正"收起→展开"这一次才把已钉行高与"上次落高宽度"清零; 单纯重建(展开全部、结构刷新)保持原高度不回退, 消除逐帧塌回再爬的抖
                        if (!wasExpanded) { appliedValH = -1f; lastFitW = -1f; refitDelay = 0; }
                        wasExpanded = true;
                        fitExpandedHeight();
                    } else {
                        //收起即清零: 下次展开按当时的值重新算, 不背着上一次的包袱
                        valLabel = null;
                        valCell = null;
                        appliedValH = 0f;
                        lastFitW = -1f;
                        refitDelay = 0;
                        wasExpanded = false;
                        Label value = new Label(this::currentValText);
                        value.setEllipsis(true);
                        value.setAlignment(Align.left);
                        t.add(value).left().minWidth(0f).growX().growY();
                    }
                }).minHeight(32f).growX().pad(0, 8, 0, 8).update(t -> {
                    fitExpandedHeight();
                    if (!var.isobj || var.objval instanceof String) return;
                    Element e = frameHover;
                    if (drawAllVars || e != null && e.isDescendantOf(t)) {
                        if (var.objval instanceof Unit unit)
                            DrawExt.info(arc.util.Tmp.v1.set(logicBuild.x, logicBuild.y), unit, var.name, Yr2Vars.themeColor);
                        else if (var.objval instanceof Building b)
                            DrawExt.info(arc.util.Tmp.v1.set(logicBuild.x, logicBuild.y), b, var.name, Yr2Vars.themeColor);
                    }
                    if (e != null && e.isDescendantOf(t)) {
                        if (var.objval instanceof Unit unit)
                            DrawExt.screenWorldLine(arc.util.Tmp.v2.set(Core.input.mouse()), unit, Yr2Vars.themeColor);
                        else if (var.objval instanceof Building b)
                            DrawExt.screenWorldLine(arc.util.Tmp.v2.set(Core.input.mouse()), b, Yr2Vars.themeColor);
                    }
                });

                //arc 的 Element.clear() 会连带 clearListeners(), 本方法首行的 clear() 已抹掉点击监听器, 必须在重建末尾重挂
                addListener(tapListener);
            }

            /**
             * 展开行的行高: 让这一行跟随**当前列宽下内容实际需要的高度**(你拖分栏把这一栏变宽/变窄, 它会跟着重排)。
             * 关键分寸在两处:
             * 1) 同一列宽内只增不减 —— 逻辑在跑时值文本忽长忽短(打印缓冲区一会儿满一会儿空), 若每帧跟着自然高走, 行高逐帧抖 = 重构前那套"记长度"的闪因, 所以锁住本宽度的峰值不让文本抖牵动。
             * 2) 换列宽时的"变矮"要等宽度停下来再落 —— 改宽度那一帧标签还没在新宽度下重新折行, 量到的值偏低, 若立刻按它落就会塌一下又弹回, 表现为下面的变量名"向上闪一下"。故宽度正在变时只维持旧高(只涨不塌), 停稳数帧后才按最终宽度干净地重排一次。
             * 为什么每帧无条件重写下限、而不是"数字没变就跳过": 展开全部或结构刷新会重建这一格、把它的下限打回未设, 引擎随即回落到值标签那个跟着文本抖的最小高; 重建不改列宽, 于是走"同一宽度只增不减"这条路, 把锁住的峰值原样补写回去。arc 的下限只赋值、不触发重排, 重复写同一数字是幂等无害的。
             */
            private void fitExpandedHeight() {
                if (valLabel == null || valCell == null) return;
                //宽度还没定型: 此刻量出来的自然高毫无意义
                float w = valLabel.getWidth();
                if (w < 60f) return;
                //自然高是引擎缩放后的实际像素, 换成设计单位再交给会乘系数的下限
                float want = Math.max(32f, ColumnLayout.design(valLabel.getPrefHeight()));

                if (appliedValH < 0f) {
                    //首次展开: 直接按当前内容长度落定
                    appliedValH = want;
                    lastFitW = w;
                    refitDelay = 0;
                } else if (Math.abs(w - lastFitW) > 0.5f) {
                    //列宽正在变: 记下新宽度、起观察期倒计时, 本帧只涨不塌
                    lastFitW = w;
                    refitDelay = 3;
                    appliedValH = Math.max(appliedValH, want);
                } else if (refitDelay > 0) {
                    //宽度停下后的观察期: 仍旧只涨, 归零那一帧才按新宽度干净落一次(允许变矮)
                    appliedValH = Math.max(appliedValH, want);
                    if (--refitDelay == 0) appliedValH = want;
                } else {
                    //常态: 只增不减, 挡住值文本每帧空↔满的抖
                    appliedValH = Math.max(appliedValH, want);
                }
                valCell.minHeight(appliedValH);
            }

            private String nameText(){
                Color bright = LogicHighlight.brighten(LogicDialog.typeColor(var, typeTmp));
                return "[#" + bright.toString().substring(0, 6) + "]" + rawName.replace("[", "[[") + "[]";
            }
     

        }

        private String formatVarText(LVar var, int currentCounter) {
            if (var.isobj) {
                if (var.objval instanceof String) return String.format("\"%s\"", var.objval);
                if (var.objval == null) return "[lightgray]null[]";
                if (var.objval instanceof Unit unit)
                    return String.format("%s#%d [%s]", unit.type.name, unit.id, MemUtil.doubleToString(unit.flag));
                if (var.objval instanceof Building b)
                    return String.format("%s#%d", b.block.name, b.id);
                return String.valueOf(var.objval);
            }
            if (Double.isNaN(var.numval) && var.name.equals("@counter")) return String.format("(%s)", currentCounter);
            return MemUtil.doubleToString(var.numval);
        }
    }

    //endregion

    //region 2. 代码与调试执行域 (Code & Execution Column): 指令执行、断点与就地编辑

    private class DebugExecutor {
        private static final LInstruction[] PAUSED = new LInstruction[0];
        private boolean paused = false;
        private int counter = 0;
        private LInstruction[] resumeInstructions = null;

        private final int[] recentExecutedLines = new int[]{-1, -1, -1, -1, -1, -1, -1, -1};
        private int recentLineIndex = 0;

        public DebugExecutor() {
        }

        public boolean isPaused() {
            return paused;
        }

        public int getCounter() {
            return counter;
        }

        public void setCounter(int counter) {
            this.counter = counter;
        }

        public int getRunningPc(int codeCount) {
            if (paused) return counter;
            if (logicBuild.executor.counter == null) return -1;
            return normalizePc((int) logicBuild.executor.counter.numval, codeCount);
        }

        public int normalizePc(int pc, int codeCount) {
            if (codeCount == 0) return 0;
            return (pc < 0 || pc >= codeCount) ? 0 : pc;
        }

        public float lineHeat(int line, boolean showLightTrace) {
            if (!showLightTrace || line < 0) return 0f;
            for (int i = 0; i < recentExecutedLines.length; i++) {
                int idx = (recentLineIndex - 1 - i + recentExecutedLines.length * 100) % recentExecutedLines.length;
                if (recentExecutedLines[idx] == line) {
                    return 1.0f - (i / (float) recentExecutedLines.length);
                }
            }
            return 0f;
        }

        public void recordExecution(int line) {
            recentExecutedLines[recentLineIndex % recentExecutedLines.length] = line;
            recentLineIndex++;
        }

        public void clearHeat() {
            Arrays.fill(recentExecutedLines, -1);
            recentLineIndex = 0;
        }

        public void setPaused(boolean p, int codeCount) {
            LExecutor exec = logicBuild.executor;
            this.paused = p;
            if (p) {
                if (exec.instructions != PAUSED) {
                    resumeInstructions = exec.instructions;
                    exec.instructions = PAUSED;
                }
                if (exec.counter != null) counter = normalizePc((int) exec.counter.numval, codeCount);
            } else {
                if (exec.instructions == PAUSED) {
                    if (resumeInstructions != null && resumeInstructions.length > 0) {
                        exec.instructions = resumeInstructions;
                    } else if (logicBuild.code != null) {
                        LAssembler asm = LAssembler.assemble(logicBuild.code, logicBuild.block.privileged);
                        exec.load(asm);
                    }
                }
            }
        }

        public void runPaused(Runnable runner) {
            LExecutor exec = logicBuild.executor;
            if (exec.instructions == PAUSED && resumeInstructions != null) exec.instructions = resumeInstructions;
            try {
                runner.run();
            } finally {
                if (paused && exec.instructions != PAUSED) {
                    resumeInstructions = exec.instructions;
                    exec.instructions = PAUSED;
                }
            }
        }

        public void stepOnce() {
            timeline.restoreRealtime();
            runPaused(this::executeTrackedStep);
            LExecutor exec = logicBuild.executor;
            if (exec.counter != null) counter = normalizePc((int) exec.counter.numval, codePanel.getCodeCount());
        }

        public void skipToBreakpoint() {
            timeline.restoreRealtime();
            runPaused(() -> {
                LExecutor exec = logicBuild.executor;
                int codeCount = codePanel.getCodeCount();
                for (int i = 0; i < 10000; i++) {
                    executeTrackedStep();
                    if (exec.instructions == null || exec.instructions.length == 0) break;
                    int line = exec.counter == null ? -1 : normalizePc((int) exec.counter.numval, codeCount);
                    if (codePanel.isBreakpoint(line)) break;
                }
            });
            LExecutor exec = logicBuild.executor;
            if (exec.counter != null) counter = normalizePc((int) exec.counter.numval, codePanel.getCodeCount());
        }

        private void executeTrackedStep() {
            LExecutor exec = logicBuild.executor;
            if (exec.instructions == null || exec.instructions.length == 0) return;

            int codeCount = codePanel.getCodeCount();
            int fromLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval, codeCount);
            String lineCode = (fromLine >= 0 && fromLine < codeCount) ? codePanel.getCodeAt(fromLine) : "";

            LVar[] vars = exec.vars;
            int n = vars == null ? 0 : vars.length;
            Object[] oldObjs = new Object[n];
            double[] oldNums = new double[n];
            boolean[] oldIsObj = new boolean[n];
            if (vars != null) {
                for (int i = 0; i < n; i++) {
                    oldObjs[i] = vars[i].objval;
                    oldNums[i] = vars[i].numval;
                    oldIsObj[i] = vars[i].isobj;
                }
            }

            exec.runOnce();

            int toLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval, codeCount);
            boolean jumped = (toLine != fromLine + 1);

            ArrayList<String> diffList = new ArrayList<>();
            HashSet<String> changedVars = new HashSet<>();
            if (vars != null) {
                for (int i = 0; i < n; i++) {
                    if (vars[i].name.equals("@counter")) continue;
                    if (vars[i].isobj != oldIsObj[i] || (vars[i].isobj ? !Objects.equals(vars[i].objval, oldObjs[i]) : vars[i].numval != oldNums[i])) {
                        String oldValStr = oldIsObj[i] ? String.valueOf(oldObjs[i]) : MemUtil.doubleToString(oldNums[i]);
                        String newValStr = vars[i].isobj ? String.valueOf(vars[i].objval) : MemUtil.doubleToString(vars[i].numval);
                        diffList.add(vars[i].name + ": " + oldValStr + " -> " + newValStr);
                        changedVars.add(vars[i].name);
                    }
                }
            }
            String diff = String.join(", ", diffList);

            recordExecution(fromLine);

            ObjectMap<String, String> snapshot = varPanel.captureSnapshot(counter);
            timeline.recordStep(fromLine, toLine, jumped, lineCode, diff, diffList, changedVars, snapshot);
        }

        public void resetSession() {
            logicBuild.updateCode(logicBuild.code);
            logicBuild.executor.textBuffer.setLength(0);
            if (paused) {
                resumeInstructions = null;
                counter = 0;
                setPaused(true, codePanel.getCodeCount());
            } else {
                if (logicBuild.executor.counter != null) logicBuild.executor.counter.numval = 0;
                counter = 0;
            }
            timeline.clear();
            clearHeat();
        }
    }



    private class CodePanel {
        /** 代码区的唯一数据源: 行文本与断点登记都在这里, codeCells 只是它按下标的投影。 */
        final ScriptModel model = new ScriptModel();
        final Table editTools;
        final Table table;
        final ScrollPane scroll;
        final ArrayList<CodeCell> codeCells = new ArrayList<>();
        CodeCell activeEditCell = null;
        /**
         * 选中行此刻是否处于可输入态。整屏只允许存在一个输入框, 且它必然长在选中行上:
         * "能输入却没有按键"的行是死锁 (点别处退不出、也回不来), 所以输入态绝不落到非选中行。
         */
        boolean activeRowEditing;
        /** 插入新行时预约的焦点行: 由 syncCells() 在统一重建前落成 activeEditCell。 */
        ScriptModel.Row pendingFocus = null;

        //region 本栏自报的宽度: 下面每个数都是 CodeCell.codeCellBuild 里真实摆下去的控件
        static final float BP_COL_W = 30f;
        static final float LINE_NUMBER_W = 35f;
        /** 断点列、指令文本、行号列各带一份右间距. */
        static final float CELL_PAD = 5f;
        /** 整行的左边距. */
        static final float ROW_PAD_L = 10f;
        static final float EDIT_BTN = 35f;
        static final int EDIT_BTN_COUNT = 5;
        //endregion

        /**
         * 代码栏绝不能被裁的是每一行两旁的固定件: 断点列 + 三段右间距 + 行号列 + 左边距。
         * 中间那条指令是自动换行的标签, 引擎规定它自报的最小宽度为 0, 所以最长那条指令不会把下限顶高。
         * 五个编辑按键只在非流水模式、且只在选中那一行出现。
         */
        float floorWidth(boolean editButtonsVisible) {
            float bare = BP_COL_W + LINE_NUMBER_W + ROW_PAD_L + CELL_PAD * 3f;
            return editButtonsVisible ? bare + EDIT_BTN * EDIT_BTN_COUNT : bare;
        }

        /** 换选中行的唯一入口: 输入态与选中行同生同灭, 不许各自为政。 */
        void setActiveRow(CodeCell cell, boolean editing) {
            activeEditCell = cell;
            activeRowEditing = cell != null && editing;
        }

        /**
         * 行点击判定 (纯函数, 便于无头测试): 这一下松手是否属于"点该行本身"。
         * 命中查询返回的是最深的可点元素 —— 图标按钮被点到时拿到的是按钮内部那张图, 不是按钮外壳,
         * 因此归属必须沿父链一路走到本行为止; 途中撞见任何交互控件就说明这一下不属于行。
         * 位移与滚动的区分交给框架: 真拖动时它会取消焦点, 调用方认那个信号。
         */
        static boolean isRowTap(Element target, Element row, Element breakpointTag) {
            //子控件收到这一下之后会立刻重造本行内容, 那时被点到的元素已离开场景树; 落点不再属于本行, 一律不作为
            if (!target.isDescendantOf(row)) return false;
            if (target == breakpointTag) return false;
            for (Element e = target; e != row; e = e.parent) {
                if (e instanceof Button || e instanceof TextField) return false;
            }
            return true;
        }

        CodePanel() {
            editTools = new Table();
            table = new Table();
            table.top().left();
            scroll = new ScrollPane(table);
            Yrailiuxa2.configurePane(scroll);
            scroll.update(() -> Yrailiuxa2.bindScrollFocus(scroll));
            buildTools();
            rebuild();
        }

        public float getDesiredWidth() {
            return model.maxTextLength() * ColumnLayout.CHAR_W + floorWidth(false);
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

        /**
         * 现在该不该画跳转线、画哪一对行: 回溯生效时取被选中的那条记录; 否则只有在暂停(单步)时取最近一条记录。
         * 仅当那一步确实是跳转才返回 {起点行, 目标行}, 其余一律 null(不画)。
         */
        int[] activeJumpPair() {
            TraceTimeline.Record r;
            if (timeline.isSnapshotActive()) r = timeline.getSelected();
            else if (executor.isPaused()) {
                ArrayList<TraceTimeline.Record> h = timeline.getHistory();
                r = h.isEmpty() ? null : h.get(h.size() - 1);
            } else r = null;
            if (r != null && r.isJump) return new int[]{r.fromLine, r.toLine};
            return null;
        }

        public void scrollToLine(int line) {
            int count = model.size();
            if (count == 0 || scroll == null) return;
            int clamped = Mathf.clamp(line, 0, count - 1);
            float percent = (float) clamped / Math.max(1, count - 1);
            scroll.setScrollPercentY(percent);
        }

        private void buildTools() {
            editTools.clear();
            editTools.table(t -> {
                //这颗旋转钮一钮两用: 回溯生效时=退出回溯回到实时, 平时=重新加载代码(回溯中哪一行/哪一步被选中, 高亮行与流水卡片已经标着, 不靠换图标提示)
                t.button(Icon.refresh, Styles.emptyi, () -> {
                    if (timeline.isSnapshotActive()) exitRollback();
                    else {
                        rebuild();
                        requestLayout();
                    }
                }).grow();
                t.button(Icon.save, Styles.emptyi, this::uploadCode).grow();
                t.button(Icon.tree, Styles.emptyi, () -> {
                    autoCollapsedDual = false;
                    if (showVarPage) showEditPage = false;
                    else {
                        showVarPage = true;
                        if (ColumnLayout.design(size.x) < DUAL_MIN_WIDTH) showEditPage = false;
                    }
                    requestLayout();
                }).grow();
            }).height(40).growX();

            editTools.row();
            editTools.table(t -> {
                ImageButton.ImageButtonStyle pauseStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
                ImageButton pauseButton = t.button(Icon.pause, pauseStyle, () -> {}).grow().get();

                t.button(Icon.left, Styles.emptyi, () -> {
                    if (!executor.isPaused()) {
                        logicPause();
                    }
                    stepOnce();
                }).grow();

                t.button(Icon.undo, Styles.emptyi, () -> {
                    if (!executor.isPaused()) {
                        logicPause();
                    }
                    skipToBreakpoint();
                }).grow();

                // 切换流水面板显示，关闭时退出回溯状态
                t.button(Icon.list, Styles.emptyi, () -> {
                    showTrace = !showTrace;
                    if (!showTrace) {
                        timeline.restoreRealtime();
                    }
                    setActiveRow(null, false);
                    codeCells.forEach(CodeCell::codeCellBuild);
                    requestDebugRefresh();
                    requestLayout();
                }).grow();

                pauseButton.clicked(() -> {
                    if (executor.isPaused()) logicRerun();
                    else logicPause();
                });

                pauseButton.update(() -> pauseButton.getStyle().imageUp = executor.isPaused() ? Icon.play : Icon.pause);
            }).height(40).growX();
        }

        /**
         * 从处理器重读已保存的脚本。只允许"手动重新拉取"与"点保存"两处调用:
         * 它会丢弃界面上尚未写回处理器的编辑, 绝不能用作增删行的手段。
         */
        void rebuild() {
            setActiveRow(null, false);
            model.loadFrom(logicBuild.code, logicBuild.block.privileged);
            syncCells();
        }

        /**
         * 数据 → 视图的唯一同步口: 行格按 ScriptModel.Row 的身份复用, 只为新出现的行造格,
         * 行号一律重派为当前下标。增删行改的是 model, 不是这里逐个造出来的控件, 所以未保存的编辑不丢。
         */
        private void syncCells() {
            ObjectMap<ScriptModel.Row, CodeCell> pool = new ObjectMap<>();
            for (CodeCell c : codeCells) pool.put(c.row, c);
            codeCells.clear();
            for (int i = 0; i < model.size(); i++) {
                ScriptModel.Row row = model.get(i);
                CodeCell cell = pool.get(row);
                if (cell == null) cell = new CodeCell(row);
                cell.line = i;
                codeCells.add(cell);
            }
            if (activeEditCell != null && !codeCells.contains(activeEditCell)) setActiveRow(null, false);
            if (pendingFocus != null) {
                //焦点要在这一遍统一重建之前定下来, 否则旧行的按键还在画, 新行又挂一套 => 两行同时有按键
                CodeCell focus = null;
                for (CodeCell c : codeCells) {
                    if (c.row == pendingFocus) focus = c;
                }
                pendingFocus = null;
                if (focus != null) setActiveRow(focus, true);
            }

            table.clear();
            table.top().left();
            for (CodeCell c : codeCells) {
                table.add(c).growX();
                table.row();
            }
            codeCells.forEach(CodeCell::codeCellBuild);
        }

        /** 在 at 处插入若干新行: 既有行的内容原封不动, 焦点落到第一条新行上。 */
        void insertRows(int at, List<ScriptModel.Row> incoming) {
            if (incoming.isEmpty()) return;
            int index = Math.max(0, Math.min(at, model.size()));
            model.insert(index, incoming);
            //新插入的行就是要立刻写代码
            pendingFocus = model.get(index);
            syncCells();
        }

        /** 删掉第 at 行。 */
        void removeRow(int at) {
            if (model.remove(at) == null) return;
            syncCells();
        }

        void uploadCode() {
            logicBuild.updateCode(model.toCode());
            if (executor.isPaused()) {
                executor.setPaused(true, getCodeCount());
                executor.setCounter(0);
            }
            timeline.clear();
            executor.clearHeat();
            rebuild();
            requestLayout();
        }



                private class CodeCell extends Table {
            /** 本行对应的数据行: 文本的唯一真相, 界面不另存一份。 */
            final ScriptModel.Row row;
            /** 行号唯一真相 = 它在 codeCells 里的下标, 由 syncCells() 统一重派, 不许自行偏移。 */
            private int line;
            private String hlSrc, hlText;
            private Label breakpointTag;
            private InputListener tapListener;

            public CodeCell(ScriptModel.Row row) {
                super();
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
                        //拖动列表超过 20 像素时, 框架会取消本行焦点并补发一次松手事件(坐标是无效值): 那一下不算点击
                        if (event.isTouchFocusCancel()) return;
                        Element t = event.targetActor;
                        if (!isRowTap(t, self, breakpointTag)) return; //单击即翻转; 落点在子控件上不作数
                        CodeCell prev = activeEditCell;
                        //点到别的行: 选中跟手挪过去, 输入态一律退出(敲进去的字当场就写进数据表, 不会跟着丢); 再点自己这一行 = 连按键一起收起
                        setActiveRow(prev == self ? null : self, false);
                        if (prev != null && prev != self) prev.codeCellBuild();
                        codeCellBuild();
                    }
                };
                codeCellBuild();
            }

            /** 本行是否处于可输入态: 只有"选中行 + 面板级输入开关"同时成立才算, 结构上杜绝"能输入却无按键"。 */
            boolean editable() {
                return activeEditCell == this && activeRowEditing;
            }

            public void codeCellBuild() {
                clear();
                breakpointTag = null;
                table(t -> {
                    breakpointTag = t.label(() -> {
                        int currentPc = executor.getRunningPc(model.size());
                        if (currentPc == line) {
                            return model.isBreakpoint(line) ? ">[red]>" : ">>";
                        }
                        return model.isBreakpoint(line) ? " [red]>" : "";
                    }).width(BP_COL_W).growY().padRight(CELL_PAD).get();
                    breakpointTag.clicked(() -> model.toggleBreakpoint(line));

                    if (editable()) {
                        t.field(row.text, s -> row.text = s).minWidth(0).grow().padRight(CELL_PAD);
                    } else {
                        Label lb = new Label(this::highlightCache);
                        lb.setWrap(true);
                        lb.setAlignment(Align.left);
                        t.add(lb).grow().padRight(CELL_PAD);
                    }

                    if (!showTrace && activeEditCell == this) {
                        t.button(Icon.pencilSmall, Styles.emptyi, () -> {
                            activeRowEditing = !activeRowEditing;
                            codeCellBuild();
                        }).size(EDIT_BTN).right();
                        t.button(Icon.addSmall, Styles.emptyi, () -> insertRows(line + 1, List.of(ScriptModel.blankRow()))).size(EDIT_BTN).right();
                        t.button(Icon.downSmall, Styles.emptyi, () -> insertRows(line + 1,
                                ScriptModel.parseSnippet(Core.app.getClipboardText(), line + 1, logicBuild.block.privileged)
                        )).size(EDIT_BTN).right();
                        t.button(Icon.refreshSmall, Styles.emptyi, () -> {
                            row.text = row.origin;
                            codeCellBuild();
                        }).size(EDIT_BTN).right();
                        t.button(Icon.cancelSmall, Styles.emptyi, () -> removeRow(line)).size(EDIT_BTN).right();
                    }

                    t.add(new Label(row.unsaved() ? "+" : "[#" + Yr2Vars.mutedHex + "]" + line)).width(LINE_NUMBER_W).right().padRight(CELL_PAD);
                }).minHeight(35).growX().padLeft(ROW_PAD_L);

                //arc 的 Element.clear() 会连带 clearListeners(), 本方法首行的 clear() 已抹掉行监听器, 必须在重建末尾重挂
                addListener(tapListener);
            }

            @Override
            public void draw() {
                // 当前代码行状态指示: 回溯高亮优先，其次暂停停驻指示与热度光轨
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
                } else if (executor.isPaused() && showLightTrace) {
                    float heat = executor.lineHeat(line, showLightTrace);
                    if (heat > 0.01f) {
                        Draw.color(Yr2Vars.themeColor, heat * 0.75f);
                        Fill.rect(x + 2f, y + height / 2f, 3f, height - 2f);
                        Draw.color(Yr2Vars.themeColor, heat * 0.10f);
                        Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                        Draw.reset();
                    }
                }

                super.draw();

                //② 回溯或单步: 当前这一步若是跳转, 就在断点那一列画一条把"发起行↔目标行"连起来、带箭头的跳转线(配色沿用流水记录的跳转)
                int[] jp = activeJumpPair();
                if (jp != null) drawJumpLink(jp[0], jp[1]);
            }

            /**
             * 在本行的断点列片段里画跳转连线的一小截: 整条线由途经的每一行各自画一段拼成(和游戏逻辑编辑区一个路子), 于是跨行也不用去算别的行的坐标。
             * 形状是一根开口的竖线: 竖脊落在断点列正中(gx), 起点行与终点行各伸一小段横臂到本行指令那一侧(xr)——起点是圆点、终点是朝指令的箭头, 中间行只画贯通竖脊。
             * 有了横臂, 起/终点的落点 x 就和竖脊不同, 看着是"从这条指令跳到那条指令", 而非一根光秃竖线。坐标把设计单位换算成实际像素。
             */
            private void drawJumpLink(int from, int to) {
                float gx = x + ColumnLayout.scene(CodePanel.ROW_PAD_L + CodePanel.BP_COL_W * 0.5f); //竖脊
                float xr = x + ColumnLayout.scene(CodePanel.ROW_PAD_L + CodePanel.BP_COL_W + 1f);    //横臂伸向指令的一侧
                float cy = y + height / 2f;
                Draw.color(Yr2Vars.jumpColor);
                Lines.stroke(1.6f, Yr2Vars.jumpColor);
                if (from == to) { //自跳(如跳回本行): 只画一个圈
                    Fill.circle(gx, cy, 3f);
                    Draw.reset();
                    return;
                }
                int lo = Math.min(from, to), hi = Math.max(from, to);
                boolean targetBelow = to > from; //目标行号更大=在屏幕上更靠下(行按号自上而下排)
                if (line > lo && line < hi) {
                    Lines.line(gx, y + 1f, gx, y + height - 1f);   //中间行: 贯通竖脊
                } else if (line == from) {
                    Lines.line(gx, cy, xr, cy);                    //起点横臂
                    Lines.line(gx, cy, gx, targetBelow ? y + 1f : y + height - 1f); //竖脊朝目标那半段
                    Fill.circle(xr, cy, 2.4f);                     //起点圆点
                } else if (line == to) { //终点行(必须显式判等: 曾经用裸 else 兜底, 于是区间外的行也全画上了箭头)
                    Lines.line(gx, cy, gx, targetBelow ? y + height - 1f : y + 1f); //竖脊朝起点那半段
                    Lines.line(gx, cy, xr, cy);                    //终点横臂
                    float s = 4f;
                    Fill.tri(xr + s, cy, xr - s, cy + s, xr - s, cy - s); //箭头指向本行指令(向右)
                }
                Draw.reset();
            }

            private String highlightCache(){
                if(hlText == null || !hlSrc.equals(row.text)){
                    hlSrc = row.text;
                    hlText = LogicHighlight.highlight(row.text);
                }
                return hlText;
            }
        }
    }

    //endregion

    //region 3. 流水时序域 (Trace & Timeline Column): 时序历史、快照回溯与流水卡片

    private class TracePanel {
        final Table tracePane;
        final Table traceHeader;
        final Table traceTable;
        final ScrollPane traceScroll;

        private Cons<TraceTimeline.Record> onSelectStep;
        private Runnable onClearHistory;

        //region 本栏自报的宽度: 下面每个数都是 buildHeader 里真实摆下去的控件
        /** 顶栏 "#" 标记的左边距. */
        static final float HEAD_PAD_L = 4f;
        /** 复制、删除两个按钮的边长. */
        static final float HEAD_BTN = 28f;
        static final int HEAD_BTN_COUNT = 2;
        /** 记录长短不一且自己横向滚动, 量不出可靠的"内容宽", 只报一个固定愿望. */
        static final float WISH_W = 200f;
        //endregion

        /**
         * 流水栏绝不能被裁的是顶栏: "#" 标记 + 两个按钮。
         * 手写的数只当兜底, 真正取的是引擎现量的顶栏最小宽: 实测顶栏比手写的宽 8(标记比一个字符宽, 按钮样式自带内边距),
         * 光靠手写的数会在最窄处把删除按钮裁掉。顶栏是纯固定控件表, 现量结果可信。
         */
        float floorWidth() {
            return Math.max(HEAD_PAD_L + ColumnLayout.CHAR_W + HEAD_BTN * HEAD_BTN_COUNT,
                    ColumnLayout.design(traceHeader.getMinWidth()));
        }

        float getDesiredWidth() {
            return WISH_W;
        }

        TracePanel() {
            traceTable = new Table();
            traceTable.top().left();
            traceScroll = new ScrollPane(traceTable);
            Yrailiuxa2.configurePane(traceScroll);
            traceScroll.update(() -> Yrailiuxa2.bindScrollFocus(traceScroll));
            traceHeader = new Table();
            traceHeader.left();
            tracePane = new Table();
            tracePane.top().left();
            buildHeader();
            rebuild();
        }

        public void setListeners(Cons<TraceTimeline.Record> onSelectStep, Runnable onClearHistory) {
            this.onSelectStep = onSelectStep;
            this.onClearHistory = onClearHistory;
        }

        /** 流水栏是自己的一个小窗口: 顶栏在上、列表在下, 两者都只在这栏的宽度里排布。 */
        private void buildHeader() {
            traceHeader.clear();
            traceHeader.left();
            traceHeader.add(new Label("[#" + Yr2Vars.mutedHex + "]#[]", Styles.outlineLabel)).left().growX().padLeft(HEAD_PAD_L);
            traceHeader.button(Icon.copySmall, Styles.emptyi, () -> {
                StringBuilder sb = new StringBuilder("# yr2lm trace\n");
                ArrayList<TraceTimeline.Record> history = timeline.getHistory();
                for (int i = history.size() - 1; i >= 0; i--) {
                    sb.append(history.get(i).toString()).append("\n");
                }
                Core.app.setClipboardText(sb.toString());
            }).size(HEAD_BTN);

            traceHeader.button(Icon.trashSmall, Styles.emptyi, () -> {
                if (onClearHistory != null) onClearHistory.run();
            }).size(HEAD_BTN);

            tracePane.clear();
            tracePane.top().left();
            tracePane.add(traceHeader).height(30f).growX().padBottom(2f);
            tracePane.row();
            tracePane.add(traceScroll).grow();
        }

        void rebuild() {
            traceTable.clear();
            traceTable.top().left();
            ArrayList<TraceTimeline.Record> history = timeline.getHistory();
            if (history.isEmpty()) {
                traceTable.add(new Label("[#" + Yr2Vars.mutedHex + "]---", Styles.outlineLabel)).padTop(20f).center();
                return;
            }
            TraceTimeline.Record selected = timeline.getSelected();
            int highlightedLine = timeline.getHighlightedLine();

            for (int i = history.size() - 1; i >= 0; i--) {
                TraceTimeline.Record rec = history.get(i);
                if (rec.isCollapsed) {
                    traceTable.table(row -> {
                        row.center().margin(1f, 2f, 1f, 2f);
                        row.add(new Label("[#" + Yr2Vars.mutedHex + "]L" + rec.fromLine + " -> L" + rec.toLine + " (" + rec.collapsedCount + ")")).center();
                        row.clicked(() -> codePanel.scrollToLine(rec.fromLine));
                    }).minHeight(20f).growX();
                    traceTable.row();
                    continue;
                }

                boolean isCurrent = (selected == rec || highlightedLine == rec.fromLine);
                traceTable.table(card -> {
                    card.left().margin(2f, 4f, 2f, 4f);

                    card.table(headRow -> {
                        headRow.left();
                        String prefix = (isCurrent ? "[#" + Yr2Vars.themeHex + "]> []" : "")
                                + "[#" + Yr2Vars.mutedHex + "]#" + rec.step + " [L" + rec.fromLine + "] ";
                        headRow.add(new Label(prefix)).left();

                        if (rec.isJump) {
                            headRow.add(new Label("[#" + Yr2Vars.jumpHex + "]-> L" + rec.toLine)).left().padRight(4f);
                        }
                    }).growX().left();
                    card.row();

                    if (rec.diffList != null && !rec.diffList.isEmpty()) {
                        for (String d : rec.diffList) {
                            card.table(diffRow -> {
                                diffRow.left().marginLeft(10f);
                                Label diffLbl = new Label("[#" + Yr2Vars.dataChangeHex + "]" + d);
                                diffLbl.setWrap(true);
                                diffRow.add(diffLbl).growX().left();
                            }).growX().left();
                            card.row();
                        }
                    }

                    // 点击某一步的流水卡片, 触发快照回溯与该所在行高亮
                    card.clicked(() -> {
                        if (onSelectStep != null) onSelectStep.get(rec);
                    });
                }).minHeight(24f).growX().padBottom(2f);
                traceTable.row();
            }
        }
    }

    //endregion

    //region 5. 宿主生命周期与调度协调 (Host Window & Coordination)

    @SuppressWarnings("this-escape")
    public LogicMonitor(String text, LogicBlock.LogicBuild logicBuild, Vec2 pos) {
        super(text, logicBuild, pos);
        this.logicBuild = logicBuild;

        // 初始化三大领域管家
        this.timeline = new TraceTimeline();
        this.executor = new DebugExecutor();
        this.layout = new ColumnLayout("yr2lm.split." + logicBuild.tile.x + ":" + logicBuild.tile.y);

        // 初始化三大纯净视图面板
        this.varPanel = new VarPanel();
        this.codePanel = new CodePanel();
        this.tracePanel = new TracePanel();

        // 事件调度绑定 (单向通知，杜绝子视图越权)
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

        // 分割线拖拽绑定: 拖拽只登记"愿望宽度", 落地仍走同一个算式(见 applyLiveWidths)。鼠标位移是实际像素, 换进算式前先除掉界面缩放
        this.varCodeSplitter = new Splitter(
            dx -> {
                layout.dragVar(ColumnLayout.design(dx), ColumnLayout.design(size.x), showVarPage, showEditPage, showTrace,
                        reportNow());
                applyLiveWidths();
            },
            () -> {
                layout.resetVar();
                requestLayout();
            },
            () -> {
                layout.saveVarRatio(ColumnLayout.design(size.x));
                requestLayout();
            }
        );

        this.codeTraceSplitter = new Splitter(
            dx -> {
                layout.dragCode(ColumnLayout.design(dx), ColumnLayout.design(size.x), showVarPage, showEditPage, showTrace,
                        reportNow());
                applyLiveWidths();
            },
            () -> {
                layout.resetCode();
                requestLayout();
            },
            () -> {
                layout.saveCodeRatio(ColumnLayout.design(size.x));
                requestLayout();
            }
        );

        init();

        size.set(540f, 500f);
        //最小宽度归 monitorTableBuild 按形态算, 这里只管高度; 首帧再举一次手, 让列宽按真实窗口宽重算(init 时 size.x 还是 0)
        minSize.y = 260f;
        requestLayout();

        monitorTable.update(() -> {
            //总闸: 全窗口只有这一处会真的重排与重取调试视图, 同一帧内多次举手只算一次
            if (pass.debugDirty) {
                pass.debugDirty = false;
                refreshDebugViews();
            }
            if (pass.layoutDirty) {
                pass.layoutDirty = false;
                monitorTableBuild();
            }
            //本帧的鼠标落点: 父容器先于子元素更新, 所以各变量行直接取这份结果
            frameHover = Core.scene == null ? null : Core.scene.hit(Yr2Vars.mouseX(), Yr2Vars.mouseY(), true);
            if (showVarPage && showEditPage && ColumnLayout.design(size.x) < DUAL_MIN_WIDTH) {
                autoCollapsedDual = true;
                showVarPage = false;
                requestLayout();
            } else if (!showVarPage && autoCollapsedDual && ColumnLayout.design(size.x) >= DUAL_RESTORE_WIDTH) {
                autoCollapsedDual = false;
                showVarPage = true;
                requestLayout();
            }
            if (logicBuild.executor.counter == null) return;
            varPanel.syncVars();
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

    /** 把算式当前的结果原样推给各栏的格子: 拖拽期间也走这里, 所以拖出来的和自动算的不会是两套宽度。 */
    private void applyLiveWidths() {
        computeWidths();
        if (varToolsCellRef != null) varToolsCellRef.width(pass.varW);
        if (varPanelCellRef != null) varPanelCellRef.width(pass.varW);
        if (editToolsCellRef != null) editToolsCellRef.width(pass.codeToolsW);
        if (editPanelCellRef != null) editPanelCellRef.width(pass.codeW);
        if (tracePanelCellRef != null) tracePanelCellRef.width(pass.traceW);
        monitorTable.invalidate();
    }

    /**
     * 整窗平铺: 顶栏行 + 主体行, 两行共用**同一套列**.
     *
     * 各栏都拿分栏算式给出的硬宽度, 谁也不跨列、谁也不吃"剩下的" —— 于是
     * "变量栏+代码栏+流水栏+分隔线 == 窗口总宽"这条等式在任意窗口宽度、任意页签组合下都成立,
     * 各栏的内容都不可能再被顶出窗口右边缘。
     */
    private void monitorTableBuild() {
        computeWidths();
        final boolean trace = traceShown();

        monitorTable.clear();
        monitorTable.top().left();
        varToolsCellRef = varPanelCellRef = null;
        editToolsCellRef = editPanelCellRef = tracePanelCellRef = null;

        //顶栏: 变量栏工具条 / 代码栏工具条; 开了流水栏时代码工具条横跨到流水栏上方(共享那一条, 不留空白),
        //但它的宽度恰好等于被跨三列之和 —— 跨列拿不到任何"多出来"的宽度, 撑不破等式
        if (showVarPage) varToolsCellRef = monitorTable.add(varPanel.tools).height(TOOLS_H).width(pass.varW);
        if (showEditPage) {
            if (showVarPage) monitorTable.add().width(ColumnLayout.SPLIT_W);
            float toolsW = pass.codeToolsW;
            editToolsCellRef = monitorTable.add(codePanel.editTools).height(TOOLS_H).width(toolsW);
            if (trace) editToolsCellRef.colspan(3);
        }
        monitorTable.row();

        //主体: 三个滚动区 + 两根实打实的分隔线(只在并排时才存在)
        if (showVarPage) varPanelCellRef = monitorTable.add(varPanel.scroll).width(pass.varW).growY();
        if (showEditPage) {
            if (showVarPage) monitorTable.add(varCodeSplitter).width(ColumnLayout.SPLIT_W).growY();
            editPanelCellRef = monitorTable.add(codePanel.scroll).width(pass.codeW).growY();
        }
        if (trace) {
            monitorTable.add(codeTraceSplitter).width(ColumnLayout.SPLIT_W).growY();
            tracePanelCellRef = monitorTable.add(tracePanel.tracePane).width(pass.traceW).growY();
        }

        //窗口的最小宽度就是"每一栏内容都不被裁"所需的那个数; 窗口自己的尺寸是实际像素, 要从设计单位换算过去
        minSize.x = ColumnLayout.scene(layout.requiredWidth(showVarPage, showEditPage, showTrace, reportNow()));
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

    /** 退出回溯、回到实时视图: 由代码区那颗旋转钮在回溯生效时调用(原变量列表上方回溯条上的同一功能，现已并进来)。 */
    private void exitRollback() {
        timeline.restoreRealtime();
        requestDebugRefresh();
        requestLayout();
    }

    /**
     * 尺寸变化只改列宽, 绝不重排结构。
     * 旧写法在这里调 monitorTableBuild(), 而重排会改变内容尺寸、再触发本方法, 两者每帧互相激励 = 整窗闪烁。
     */
    @Override
    protected void onResized() {
        super.onResized();
        if (pass.applyingWidths) return;
        pass.applyingWidths = true;
        try {
            applyLiveWidths();
        } catch (Throwable e) {
            Log.err("yr2lm applyLiveWidths failed: " + e, e);
            pass.layoutDirty = true; // 异常时标记帧末重试, 保证刷新自愈而非永久锁死
        } finally {
            pass.applyingWidths = false;
        }
    }

    @Override
    public LogicBlock.LogicBuild getBuilding() {
        return logicBuild;
    }
    //endregion
}