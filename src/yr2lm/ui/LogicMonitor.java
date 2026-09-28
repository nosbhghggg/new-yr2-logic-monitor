package yr2lm.ui;

import arc.Core;
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
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements;
import mindustry.logic.LVar;
import mindustry.logic.LogicDialog;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;
import yr2lm.util.LogicHighlight;
import yr2lm.util.MemUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 逻辑块调试器面板。
 *
 * 架构特性:
 * 1. 内容驱动动态分栏 (方案三): 分栏初值按内容自然宽度智能分配 (变量表扫描名字/值长度, 代码区扫描最长指令),
 *    配合自由拖拽分割线 (Splitter) 无级微调, 松手自动记忆比例 (Core.settings 持久化, 仅存比例非绝对像素), 双击分割线重置为内容初值。
 * 2. 原地组件刷新: 变量表、代码表、流水表原地清空重装，保留 ScrollPane 实例与视口焦点。
 * 3. 运行指示与调试高亮解耦: 运行态仅更新行号文本指针，暂停/单步调试态激活极光绿指示条与光轨。
 * 4. 复合指令差分捕获: 完整记录单步修改的所有变量差分值。
 */
public class LogicMonitor extends Monitor {
    /** 暂停时替换 executor 的空指令表, 用同一性判断当前是否处于暂停态。 */
    private static final LInstruction[] PAUSED = new LInstruction[0];
    private static final Pattern CONST_NAME = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");

    private final LogicBlock.LogicBuild logicBuild;
    private static final Color typeTmp = new Color();
    private static final float NAME_COL = 130f;
    private static final float DUAL_MIN_WIDTH = 700f;

    private boolean showVarPage = true, showEditPage = false;
    private boolean autoCollapsedDual = false;
    private String varFilter = "";
    private boolean filterCc = false, filterW = false;
    private boolean drawAllVars = false;
    private boolean pause = false;
    private int counter, varSignature;
    private LInstruction[] resumeInstructions;

    // 动态分栏与自由拖拽分割线 (Splitter) 支持: 布局占位 12px 保证手机可拖, 视觉仅中央 2px 细线
    private static final float SPLIT_W = 12f;
    /** 默认字体单字符近似渲染宽度 (变量列宽/代码区自然宽度共用)。 */
    private static final float CHAR_W = 8.5f;
    /** 流水区最低保底宽度: 足以容纳头部 (#标签+复制+垃圾桶)、折叠行与右下角缩放手柄, 压不扁挤出窗。 */
    private static final float TRACE_MIN_W = 170f;
    /** 变量栏最低保底宽度: 容纳工具栏两行 (搜索框 + 三个开关按钮)。 */
    private static final float VAR_MIN_W = 200f;

    /** 代码栏最低保底宽度: 断点列+行号+边距 90px; 编辑按键模式下还需容纳 5 个按需按键 (175px)。 */
    private float codeMinW() {
        return showTrace ? 160f : 280f;
    }
    private float userVarW = -1f;
    private float userCodeW = -1f;
    private boolean varMemLoaded = false, codeMemLoaded = false;
    /** 上次参与布局的窗口宽度: 检测 resize, 把拖拽分栏值按比例迁移 (分栏偏好以比例形式存在, 不随窗口变形失效)。 */
    private float lastTrackedW = -1f;
    /** 本方块的持久化键前缀: 每个逻辑块独立寄存一套分栏偏好, 互不污染 (键 = 坐标)。 */
    private final String posKey;
    private float dynNameCol = 130f;
    private int maxValContentLen = 4;
    // 拖拽中的活宽度直改引用: 拖拽过程绝不 clear/重建主表 (否则分割线被移出场景树, 触摸焦点被强制取消, 拖拽断链)
    private Cell<?> varToolsCellRef, varPanelCellRef, rollbackCellRef, editPanelCellRef;
    private final Splitter varCodeSplitter;
    private final Splitter codeTraceSplitter;

    // 单行手动展开状态 (按变量名跨重建保持): 流水回溯/快照刷新重建变量表时, 展开意图不丢失
    private final ObjectMap<String, Boolean> manualExpandedVars = new ObjectMap<>();
    // 全局完整显示开关: 所有变量值不折叠
    private boolean allExpanded = false;

    // 核心持久组件 (一次创建，终身挂载，原地响应)
    private final Table varTools, editTools, traceRollbackBar;
    private final Table varTable, codeTable, traceTable;
    private final ScrollPane varPanel, editPanel, traceScroll;
    private final Table tracePane;

    private final ArrayList<LVar> constants, links;
    private final ArrayList<CodeCell> codeCells;
    private final HashSet<Integer> breakpoints;

    // 智能流水与光轨数据模型
    private static final int TRACE_CAPACITY = 64;
    private final ArrayList<TraceRecord> traceHistory = new ArrayList<>();
    private int traceStepCounter = 0;
    private boolean showTrace = false;
    private TraceRecord selectedTrace = null;
    private int highlightedTraceLine = -1;
    private boolean showLightTrace = true;

    // 独立执行历史环形缓冲区 (不受流水折叠抑制，保障代码行时间轴光轨真实有效)
    private final int[] recentExecutedLines = new int[]{-1, -1, -1, -1, -1, -1, -1, -1};
    private int recentLineIndex = 0;

    private void recordExecution(int line) {
        recentExecutedLines[recentLineIndex % recentExecutedLines.length] = line;
        recentLineIndex++;
    }

    private float lineHeat(int line) {
        if (!showLightTrace || line < 0) return 0f;
        for (int i = 0; i < recentExecutedLines.length; i++) {
            int idx = (recentLineIndex - 1 - i + recentExecutedLines.length * 100) % recentExecutedLines.length;
            if (recentExecutedLines[idx] == line) {
                return 1.0f - (i / (float) recentExecutedLines.length);
            }
        }
        return 0f;
    }

    private int lineJumpTarget(int line) {
        if (!showLightTrace || traceHistory.isEmpty()) return -1;
        int size = traceHistory.size();
        for (int i = 0; i < Math.min(8, size); i++) {
            TraceRecord rec = traceHistory.get(size - 1 - i);
            if (rec.fromLine == line && rec.isJump) {
                return rec.toLine;
            }
        }
        return -1;
    }


    /** 分栏自由拖拽分割线: 支持左右无级拖拽调整分栏宽度，双击快速重置为内容自适应初值，松手自动记忆比例。 */
    private class Splitter extends Table {
        private boolean isDown = false;
        private boolean moved = false;

        public Splitter(arc.func.Cons<Float> onDrag, Runnable onReset, Runnable onRelease) {
            touchable = Touchable.enabled;
            addListener(new InputListener() {
                float startMouseX;
                long lastClickTime = 0;

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    startMouseX = Core.input.mouseX();
                    isDown = true;
                    moved = false;
                    long now = Time.millis();
                    if (now - lastClickTime < 300 && onReset != null) {
                        onReset.run();
                        lastClickTime = 0;
                        event.stop();
                        return true;
                    }
                    lastClickTime = now;
                    event.stop();
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    float curMouseX = Core.input.mouseX();
                    float dx = curMouseX - startMouseX;
                    startMouseX = curMouseX;
                    if (dx != 0f) moved = true;
                    onDrag.get(dx);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    isDown = false;
                    // 仅真实拖拽后松手才记忆+规范化重建; 纯点击与双击重置不再重复重建
                    if (moved && onRelease != null) onRelease.run();
                }
            });
        }

        @Override
        public void draw() {
            super.draw();
            Element h = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
            boolean hover = (h != null && (h == this || h.isDescendantOf(this)));
            if (isDown || hover) {
                // 悬浮/按下时整条淡色铺底: 12px 触控热区可视化, 手机玩家也能看清可拖区域
                Draw.color(Yr2Vars.themeColor, isDown ? 0.22f : 0.10f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height);
            }
            Draw.color(isDown || hover ? Yr2Vars.themeColor : Yr2Vars.mutedColor, isDown ? 0.90f : (hover ? 0.60f : 0.20f));
            Fill.rect(x + width / 2f, y + height / 2f, 2f, height);
            Draw.reset();
        }
    }

    /** 变量表单格。名字为静态内容允许换行 (一次定型绝不抖动); 值默认单行省略, 点击/悬浮/全局开关展开完整内容。
     *  展开态为快照文本 (不挂每帧轮询, 单次展开期间内容静止), 行高由内容自然决定 — 无需峰值预留, 也不会闪烁。 */
    private class VarCell extends Table {
        public LVar var;
        private final String rawName;
        private boolean hoverExpanded = false;
        private boolean hoverSuppressed = false;

        public VarCell(LVar varInit, String varName) {
            super();
            this.var = varInit;
            this.rawName = varName;
            touchable = Touchable.enabled;
            // 悬浮临时展开 (仅桌面端): 手机端无悬浮, 点击为主路径
            if (!Core.app.isAndroid()) {
                addListener(new InputListener() {
                    @Override
                    public void enter(InputEvent event, float x, float y, int pointer, Element from) {
                        if (!hoverExpanded) {
                            hoverExpanded = true;
                            rebuildCell();
                        }
                    }

                    @Override
                    public void exit(InputEvent event, float x, float y, int pointer, Element to) {
                        if (hoverExpanded || hoverSuppressed) {
                            hoverExpanded = false;
                            hoverSuppressed = false;
                            rebuildCell();
                        }
                    }
                });
            }
            clicked(() -> {
                boolean target = !isExpanded();
                setManualExpanded(target);
                // 点击收起后抑制悬浮展开 (鼠标移开该行自动解除), 否则悬浮意图会瞬间覆盖收起意图, 点击形同失效
                hoverSuppressed = !target;
                rebuildCell();
            });
            rebuildCell();
        }

        private boolean manualExpanded() {
            return manualExpandedVars.get(rawName, false);
        }

        private void setManualExpanded(boolean v) {
            if (v) manualExpandedVars.put(rawName, true);
            else manualExpandedVars.remove(rawName);
        }

        private boolean isExpanded() {
            return allExpanded || manualExpanded() || (hoverExpanded && !hoverSuppressed);
        }

        /** 与折叠态轮询同源的取值逻辑 (含回溯快照与差分标记)。 */
        private String currentValText() {
            if (selectedTrace != null && selectedTrace.varSnapshot != null && selectedTrace.varSnapshot.containsKey(var.name)) {
                String val = selectedTrace.varSnapshot.get(var.name);
                if (selectedTrace.changedVarNames != null && selectedTrace.changedVarNames.contains(var.name)) {
                    return "[#" + Yr2Vars.dataChangeHex + "]" + val + " [diff]";
                }
                return val;
            }
            return formatVarText(this.var);
        }

        public void rebuildCell() {
            clear();
            table(t -> {
                // 名字列: 静态内容换行完整显示, 折行高度一次定型, 绝不参与抖动
                Label name = new Label(this::nameText);
                name.setWrap(true);
                name.setAlignment(Align.left, Align.top);
                t.add(name).left().top().width(dynNameCol).growY().padRight(8f);

                boolean expanded = isExpanded();
                if (expanded) {
                    // 展开态: 快照式完整显示 (不挂每帧轮询), 行高由历史最大长度预锁
                    Label value = new Label(currentValText());
                    value.setWrap(true);
                    value.setAlignment(Align.left, Align.top);
                    t.add(value).left().top().minWidth(0f).growX().growY();
                } else {
                    Label value = new Label(this::currentValText);
                    value.setEllipsis(true);
                    value.setAlignment(Align.left);
                    t.add(value).left().minWidth(0f).growX().growY();
                }
            }).minHeight(32f).growX().pad(0, 8, 0, 8).update(t -> {
                if (!var.isobj || var.objval instanceof String) return;
                Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
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
        }

        private String nameText(){
            Color bright = LogicHighlight.brighten(LogicDialog.typeColor(var, typeTmp));
            return "[#" + bright.toString().substring(0, 6) + "]" + rawName.replace("[", "[[") + "[]";
        }
    }

    private String formatVarText(LVar var) {
        if (var.isobj) {
            if (var.objval instanceof String) return String.format("\"%s\"", var.objval);
            if (var.objval == null) return "[lightgray]null[]";
            if (var.objval instanceof Unit unit)
                return String.format("%s#%d [%s]", unit.type.name, unit.id, MemUtil.doubleToString(unit.flag));
            if (var.objval instanceof Building b)
                return String.format("%s#%d", b.block.name, b.id);
            return String.valueOf(var.objval);
        }
        if (Double.isNaN(var.numval) && var.name.equals("@counter")) return String.format("(%s)", counter);
        return MemUtil.doubleToString(var.numval);
    }

    // 编辑按键按需显示: 仅当前点击选中的代码行展示 5 个功能按键, 其余行纯代码; 流水模式下彻底关闭
    private CodeCell activeEditCell = null;

    /** 代码行单格。 */
    private class CodeCell extends Table {
        private final int line;
        public String code;
        private final String codeOrigin;
        private String hlSrc, hlText;
        private boolean edit;
        private Label breakpointTag;
        private long lastTapTime = 0;

        public CodeCell(int line, String code, boolean edit) {
            super();
            this.line = line;
            this.code = code;
            this.codeOrigin = code;
            this.edit = edit;
            touchable = Touchable.enabled;
            final CodeCell self = this;
            // 行级点击切换编辑按键显隐; 断点列/编辑输入框/功能按键有自己的语义, 不触发行级切换
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    return true;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                    Element t = event.targetActor;
                    if (t == breakpointTag || t instanceof TextField || t instanceof Button) return;
                    long now = Time.millis();
                    // 双击: 工具栏已弹出时保持弹出 — 否则第二次单击的 toggle 会瞬间收起, 双击形同失效
                    if (now - lastTapTime < 300 && activeEditCell == self) {
                        lastTapTime = now;
                        return;
                    }
                    lastTapTime = now;
                    CodeCell prev = activeEditCell;
                    activeEditCell = (prev == self) ? null : self;
                    if (prev != null && prev != self) prev.codeCellBuild();
                    codeCellBuild();
                }
            });
            codeCellBuild();
        }

        public CodeCell(int line, String code) {
            this(line, code, false);
        }

        public void codeCellBuild() {
            clear();
            breakpointTag = null;
            table(t -> {
                breakpointTag = t.label(() -> {
                    int currentPc = pause ? counter : (logicBuild.executor.counter == null ? -1 : normalizePc((int) logicBuild.executor.counter.numval));
                    if (currentPc == line) {
                        return breakpoints.contains(line) ? ">[red]>" : ">>";
                    }
                    return breakpoints.contains(line) ? " [red]>" : "";
                }).width(30).growY().padRight(5).get();
                breakpointTag.clicked(() -> {
                    if (breakpoints.contains(line)) breakpoints.remove(line);
                    else breakpoints.add(line);
                });

                if (edit) {
                    t.field(code, s -> code = s).minWidth(0).grow().padRight(5);
                } else {
                    Label lb = new Label(this::highlightCache);
                    lb.setWrap(true);
                    lb.setAlignment(Align.left);
                    t.add(lb).grow().padRight(5);
                }

                // 编辑按键按需显示: 仅当前选中的代码行挂载, 流水模式下彻底卸除
                if (!showTrace && activeEditCell == this) {
                    t.button(Icon.pencilSmall, Styles.emptyi, () -> {
                        edit = !edit;
                        codeCellBuild();
                    }).size(35).right();
                    t.button(Icon.addSmall, Styles.emptyi, () -> {
                        codeCells.add(codeCells.indexOf(this) + 1, new CodeCell(-Math.abs(line), "", true));
                        rebuildCodeTable();
                    }).size(35).right();
                    t.button(Icon.downSmall, Styles.emptyi, () -> {
                        String clipboard = Core.app.getClipboardText();
                        clipboard = clipboard == null ? "" : clipboard.replace("\r\n", "\n");
                        StringBuilder sb = new StringBuilder();
                        ArrayList<CodeCell> clipboardList = LAssembler.read(clipboard, true).map(ls -> {
                            if (ls instanceof LStatements.JumpStatement jumpStatement)
                                jumpStatement.destIndex += Math.abs(line) + 1;
                            sb.setLength(0);
                            ls.write(sb);
                            return new CodeCell(-Math.abs(line), sb.toString(), true);
                        }).list();
                        codeCells.forEach(codeCell -> {
                            LStatement ls = LAssembler.read(codeCell.code, true).get(0);
                            if (ls instanceof LStatements.JumpStatement jumpStatement && jumpStatement.destIndex > Math.abs(line)) {
                                jumpStatement.destIndex += clipboardList.size();
                                sb.setLength(0);
                                ls.write(sb);
                                codeCell.code = sb.toString();
                            }
                        });
                        codeCells.addAll(Math.abs(line) + 1, clipboardList);
                        rebuildCodeTable();
                    }).size(35).right();
                    t.button(Icon.refreshSmall, Styles.emptyi, () -> {
                        code = codeOrigin;
                        codeCellBuild();
                    }).size(35).right();
                    t.button(Icon.cancelSmall, Styles.emptyi, () -> {
                        codeCells.remove(this);
                        rebuildCodeTable();
                    }).size(35).right();
                }

                t.add(new Label(line < 0 ? "+" : "[#" + Yr2Vars.mutedHex + "]" + line)).width(35).right().padRight(5);
            }).minHeight(35).growX().padLeft(10);
        }

        @Override
        public void draw() {
            if (showTrace && highlightedTraceLine == line) {
                // 回溯选中行高亮: 极光翠绿探照灯整行背景高亮与聚焦边框
                Draw.color(Yr2Vars.themeColor, 0.28f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                Lines.stroke(2f, Yr2Vars.themeColor);
                Lines.rect(x + 1f, y + 1f, width - 2f, height - 2f);
                Draw.reset();
            } else if (pause && counter == line) {
                // 仅在单步/断点/暂停调试态精准显示极光绿指示条与背景微光 (正常全速运行态静默无绿光干扰)
                Draw.color(Yr2Vars.themeColor, 0.90f);
                Fill.rect(x + 2f, y + height / 2f, 4f, height - 2f);
                Draw.color(Yr2Vars.themeColor, 0.16f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                Draw.reset();
            } else if (pause && showLightTrace) {
                // 时间轴热度微光: 仅在调试暂停态显示
                float heat = lineHeat(line);
                if (heat > 0.01f) {
                    Draw.color(Yr2Vars.themeColor, heat * 0.75f);
                    Fill.rect(x + 2f, y + height / 2f, 3f, height - 2f);
                    Draw.color(Yr2Vars.themeColor, heat * 0.10f);
                    Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                    Draw.reset();
                }
            }

            super.draw();

            if (pause && showLightTrace && !showTrace) {
                int target = lineJumpTarget(line);
                if (target >= 0) {
                    String jumpTag = "-> L" + target;
                    Fonts.outline.draw(jumpTag, x + width - 40f, y + height - 4f, Yr2Vars.jumpColor, 0.30f, false, Align.right);
                }
            }
        }

        private String highlightCache(){
            if(hlText == null || !hlSrc.equals(code)){
                hlSrc = code;
                hlText = LogicHighlight.highlight(code);
            }
            return hlText;
        }
    }

    /** 智能流水数据模型。 */
    public static class TraceRecord {
        public final int step;
        public int fromLine;
        public int toLine;
        public final boolean isJump;
        public final boolean isCollapsed;
        public int collapsedCount;
        public final String code;
        public final String diff;
        public final ArrayList<String> diffList;
        public final HashSet<String> changedVarNames;
        public final ObjectMap<String, String> varSnapshot;

        public TraceRecord(int step, int fromLine, int toLine, boolean isJump, boolean isCollapsed, int collapsedCount, String code, String diff, ArrayList<String> diffList, HashSet<String> changedVarNames, ObjectMap<String, String> varSnapshot) {
            this.step = step;
            this.fromLine = fromLine;
            this.toLine = toLine;
            this.isJump = isJump;
            this.isCollapsed = isCollapsed;
            this.collapsedCount = collapsedCount;
            this.code = code;
            this.diff = diff;
            this.diffList = diffList;
            this.changedVarNames = changedVarNames;
            this.varSnapshot = varSnapshot;
        }

        @Override
        public String toString() {
            if (isCollapsed) return "[L" + fromLine + " -> L" + toLine + "] " + collapsedCount + " steps";
            String j = isJump ? " -> L" + toLine : "";
            String d = (diff != null && !diff.isEmpty()) ? " (" + diff + ")" : "";
            return "#" + step + " [L" + fromLine + "] " + code + j + d;
        }
    }

    private void recordStep(int fromLine, int toLine, boolean isJump, String code, String diff, ArrayList<String> diffList, HashSet<String> changedVarNames, ObjectMap<String, String> snapshot) {
        boolean isKeyMilestone = isJump || !diff.isEmpty();
        if (!isKeyMilestone) {
            if (!traceHistory.isEmpty()) {
                TraceRecord last = traceHistory.get(traceHistory.size() - 1);
                if (last.isCollapsed) {
                    last.toLine = toLine;
                    last.collapsedCount++;
                    return;
                }
            }
            traceStepCounter++;
            TraceRecord collapsed = new TraceRecord(traceStepCounter, fromLine, toLine, false, true, 1, code, "", null, null, snapshot);
            if (traceHistory.size() >= TRACE_CAPACITY) traceHistory.remove(0);
            traceHistory.add(collapsed);
            return;
        }

        traceStepCounter++;
        TraceRecord rec = new TraceRecord(traceStepCounter, fromLine, toLine, isJump, false, 1, code, diff, diffList, changedVarNames, snapshot);
        if (traceHistory.size() >= TRACE_CAPACITY) traceHistory.remove(0);
        traceHistory.add(rec);
    }

    /**
     * 执行指针规范化: 原版 runOnce 为「指针先自增再执行指令」, end 指令实际把指针置于指令表末尾 (越界哨兵),
     * 依靠下一轮 runOnce 的越界检查归零实现「回到开头」。监视器暂停期间不跑 runOnce, 哨兵值若不翻译,
     * 执行指针高亮会因无行号匹配而消失、流水行号随之整体偏移。此处对齐原版下一轮的真实行为: 越界即回 0。
     * <p>
     * 注意: 绝不可用 exec.instructions.length 判界 — runPaused 返回后指令表已被换回空哨兵 PAUSED,
     * 长度恒 0 会让所有指针归零; 必须用 codeCells.size() (与指令表等长且生命周期稳定)。
     */
    private int normalizePc(int pc) {
        int len = codeCells.size();
        if (len == 0) return 0;
        return (pc < 0 || pc >= len) ? 0 : pc;
    }

    private void executeTrackedStep() {
        LExecutor exec = logicBuild.executor;
        if (exec.instructions == null || exec.instructions.length == 0) return;

        int fromLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval);
        String lineCode = (fromLine >= 0 && fromLine < codeCells.size()) ? codeCells.get(fromLine).code : "";

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

        int toLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval);
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

        // 独立记录执行轨迹 (不受流水摘要折叠抑制)
        recordExecution(fromLine);

        ObjectMap<String, String> snapshot = new ObjectMap<>();
        if (vars != null) {
            for (LVar v : vars) snapshot.put(v.name, formatVarText(v));
        }
        for (LVar v : constants) snapshot.put(v.name, formatVarText(v));

        recordStep(fromLine, toLine, jumped, lineCode, diff, diffList, changedVars, snapshot);
    }

    @SuppressWarnings("this-escape")
    public LogicMonitor(String text, LogicBlock.LogicBuild logicBuild, Vec2 pos) {
        super(text, logicBuild, pos);
        this.logicBuild = logicBuild;
        this.posKey = "yr2lm.split." + logicBuild.tile.x + ":" + logicBuild.tile.y;

        varTools = new Table();
        editTools = new Table();
        traceRollbackBar = new Table();

        // 永久挂载的内部组件与滚动视口 (零重复创建，永久稳定)
        varTable = new Table();
        varTable.top().left();
        varPanel = new ScrollPane(varTable);
        Yrailiuxa2.configurePane(varPanel);
        varPanel.update(() -> Yrailiuxa2.bindScrollFocus(varPanel));

        codeTable = new Table();
        codeTable.top().left();
        editPanel = new ScrollPane(codeTable);
        Yrailiuxa2.configurePane(editPanel);
        editPanel.update(() -> Yrailiuxa2.bindScrollFocus(editPanel));

        traceTable = new Table();
        traceTable.top().left();
        traceScroll = new ScrollPane(traceTable);
        Yrailiuxa2.configurePane(traceScroll);
        traceScroll.update(() -> Yrailiuxa2.bindScrollFocus(traceScroll));

        tracePane = new Table();
        tracePane.top().left();
        buildTraceHeader();

        constants = new ArrayList<>();
        links = new ArrayList<>();
        codeCells = new ArrayList<>();
        breakpoints = new HashSet<>();

        varCodeSplitter = new Splitter(dx -> {
            float curW = getEffectiveVarWidth();
            float maxAllow = varMaxAllow();
            userVarW = Mathf.clamp(curW + dx, VAR_MIN_W, maxAllow);
            // 拖拽中绝不 monitorTableBuild(): clear 会把 Splitter 移出场景树并强制取消触摸焦点, 拖一下就断链
            applyLiveWidths();
        }, () -> {
            userVarW = -1f;
            varMemLoaded = true;
            Core.settings.put(posKey + ".varRatio", 0f);
            monitorTableBuild();
        }, () -> {
            if (userVarW > 0f) Core.settings.put(posKey + ".varRatio", userVarW / Math.max(1f, size.x));
            monitorTableBuild();
        });

        codeTraceSplitter = new Splitter(dx -> {
            float curW = getEffectiveCodeWidth();
            // 单代码栏模式 (变量页关闭) 下不存在变量区, 上限不扣幽灵 varW
            float maxAllow = Math.max(codeMinW(), size.x - (showVarPage ? getEffectiveVarWidth() + SPLIT_W : 0f) - SPLIT_W - TRACE_MIN_W);
            userCodeW = Mathf.clamp(curW + dx, codeMinW(), maxAllow);
            applyLiveWidths();
        }, () -> {
            userCodeW = -1f;
            codeMemLoaded = true;
            Core.settings.put(posKey + ".codeRatio", 0f);
            monitorTableBuild();
        }, () -> {
            if (userCodeW > 0f) Core.settings.put(posKey + ".codeRatio", userCodeW / Math.max(1f, size.x));
            monitorTableBuild();
        });

        varToolsBuild();
        editToolsBuild();
        init();

        size.set(540f, 500f);
        minSize.set(380f, 260f);

        monitorTable.update(() -> {
            // 双栏自适应收缩与恢复
            if (showVarPage && showEditPage && size.x < DUAL_MIN_WIDTH) {
                autoCollapsedDual = true;
                showVarPage = false;
                monitorTableBuild();
            } else if (!showVarPage && autoCollapsedDual && size.x >= DUAL_MIN_WIDTH) {
                autoCollapsedDual = false;
                showVarPage = true;
                monitorTableBuild();
            }
            if (logicBuild.executor.counter == null) return;
            LExecutor exec = logicBuild.executor;
            int curSignature = (exec.vars == null ? 0 : exec.vars.length) ^ (exec.counter == null ? 0 : exec.counter.hashCode());
            if (curSignature != varSignature) {
                rebuildVarTable();
            }
        });
    }

    @Override
    public void init() {
        rebuildVarTable();
        rebuildCodeTable();
        rebuildTraceTable();
        rebuildRollbackBar();
        monitorTableBuild();
    }

    /** 调试视图标准刷新: 变量表 + 流水 + 回溯条原地重装 (布局不变)。 */
    private void refreshDebugViews() {
        rebuildVarTable();
        rebuildTraceTable();
        rebuildRollbackBar();
    }

    @Override
    public void removeFromScene() {
        setPaused(false);
        super.removeFromScene();
    }

    /** 变量区拖拽上限: 为其余分栏强制保留最低宽度 — 每栏的最低保底集中声明于此 (VAR_MIN_W/codeMinW/TRACE_MIN_W), 杜绝拖溢出。 */
    private float varMaxAllow() {
        float reserve = showTrace ? TRACE_MIN_W + SPLIT_W * 2f + codeMinW() : codeMinW() + SPLIT_W;
        return Math.max(VAR_MIN_W, size.x - reserve);
    }

    private float getEffectiveVarWidth() {
        if (!varMemLoaded && size.x > 1f) {
            varMemLoaded = true;
            float saved = Core.settings.getFloat(posKey + ".varRatio", 0f);
            if (saved > 0f && userVarW < 0f) userVarW = saved * size.x;
        }
        if (userVarW > 0f) {
            return Mathf.clamp(userVarW, VAR_MIN_W, varMaxAllow());
        }
        float desired = dynNameCol + Math.min(240f, maxValContentLen * CHAR_W + 20f) + 24f;
        if (showTrace) {
            return Mathf.clamp(desired, 240f, Math.max(240f, size.x * 0.36f));
        } else {
            return Mathf.clamp(desired, 260f, Math.max(260f, size.x * 0.48f));
        }
    }

    /** 代码区内容自然宽度: 最长指令文本 + 断点列/行号列/边距固定开销 (编辑按键已改为按需显示, 不占常驻宽度)。 */
    private float codeContentDesired() {
        int maxLen = 6;
        for (CodeCell c : codeCells) {
            if (c.code != null) maxLen = Math.max(maxLen, c.code.length());
        }
        return maxLen * CHAR_W + 90f;
    }

    private float getEffectiveCodeWidth() {
        if (!codeMemLoaded && size.x > 1f) {
            codeMemLoaded = true;
            float saved = Core.settings.getFloat(posKey + ".codeRatio", 0f);
            if (saved > 0f && userCodeW < 0f) userCodeW = saved * size.x;
        }
        // 单代码栏模式 (变量页关闭) 下不存在变量区, 绝不可扣减幽灵 varW
        boolean dual = showVarPage;
        float varW = dual ? getEffectiveVarWidth() : 0f;
        float remain = Math.max(100f, size.x - varW - SPLIT_W * (dual ? 2f : 1f));
        if (userCodeW > 0f) {
            return Mathf.clamp(userCodeW, codeMinW(), remain - TRACE_MIN_W);
        }
        if (!showTrace) return remain;
        // 流水三栏: 按双方内容饥渴度比例分配，双方都拿足时余量全给流水区
        float codeDesired = codeContentDesired();
        float traceDesired = 200f;
        float total = codeDesired + traceDesired;
        if (total <= remain) return codeDesired;
        return Mathf.clamp(remain * codeDesired / total, 200f, remain - TRACE_MIN_W);
    }

    /** 拖拽中的活宽度直改: 只更新已记录的 Cell 宽度并原地重排, 元素绝不离开场景树, 触摸焦点全程保持。 */
    private void applyLiveWidths() {
        float varW = getEffectiveVarWidth();
        float codeW = getEffectiveCodeWidth();
        if (varToolsCellRef != null) varToolsCellRef.width(varW);
        if (varPanelCellRef != null) varPanelCellRef.width(varW);
        if (rollbackCellRef != null) rollbackCellRef.width(varW);
        if (editPanelCellRef != null) editPanelCellRef.width(codeW);
        monitorTable.invalidate();
    }

    /** 响应式单表平铺布局: 融合【内容智能初值】与【自由分栏分割线 (Splitter)】，支持无级手动微调与自动不换行适配。 */
    private void monitorTableBuild() {
        // resize 比例迁移: 窗口宽度一变, 拖拽分栏值按新旧宽度比同步缩放 (分栏偏好以比例形式存在)
        if (lastTrackedW > 1f && size.x > 1f && Math.abs(size.x - lastTrackedW) > 0.5f) {
            float ratio = size.x / lastTrackedW;
            if (userVarW > 0f) userVarW *= ratio;
            if (userCodeW > 0f) userCodeW *= ratio;
        }
        if (size.x > 1f) lastTrackedW = size.x;

        monitorTable.clear();
        monitorTable.top().left();
        varToolsCellRef = varPanelCellRef = rollbackCellRef = editPanelCellRef = null;

        if (showVarPage && showEditPage) {
            if (showTrace) {
                float varW = getEffectiveVarWidth();
                float codeW = getEffectiveCodeWidth();

                varToolsCellRef = monitorTable.add(varTools).height(80).width(varW);
                monitorTable.add().width(SPLIT_W);
                monitorTable.add(editTools).colspan(3).height(80).growX();
                monitorTable.row();

                if (selectedTrace != null) {
                    rollbackCellRef = monitorTable.add(traceRollbackBar).height(34).width(varW);
                    monitorTable.add().width(SPLIT_W);
                    monitorTable.add().colspan(3);
                    monitorTable.row();
                }

                varPanelCellRef = monitorTable.add(varPanel).width(varW).growY();
                monitorTable.add(varCodeSplitter).width(SPLIT_W).growY();
                editPanelCellRef = monitorTable.add(editPanel).width(codeW).growY();
                monitorTable.add(codeTraceSplitter).width(SPLIT_W).growY();
                monitorTable.add(tracePane).grow();
            } else {
                float varW = getEffectiveVarWidth();

                varToolsCellRef = monitorTable.add(varTools).height(80).width(varW);
                monitorTable.add().width(SPLIT_W);
                monitorTable.add(editTools).height(80).growX();
                monitorTable.row();

                if (selectedTrace != null) {
                    rollbackCellRef = monitorTable.add(traceRollbackBar).height(34).width(varW);
                    monitorTable.add().width(SPLIT_W);
                    monitorTable.add();
                    monitorTable.row();
                }

                varPanelCellRef = monitorTable.add(varPanel).width(varW).growY();
                monitorTable.add(varCodeSplitter).width(SPLIT_W).growY();
                monitorTable.add(editPanel).grow();
            }
        } else if (showVarPage) {
            monitorTable.add(varTools).height(80).growX();
            monitorTable.row();
            if (selectedTrace != null) {
                monitorTable.add(traceRollbackBar).height(34).growX();
                monitorTable.row();
            }
            monitorTable.add(varPanel).grow();
        } else if (showEditPage) {
            if (showTrace) {
                // 单代码栏 + 流水双栏: 与三栏模式同源的饥渴度分配, 全项目零写死比例
                float remain = Math.max(codeMinW(), size.x - SPLIT_W);
                float codeW;
                if (userCodeW > 0) {
                    codeW = Mathf.clamp(userCodeW, codeMinW(), remain - TRACE_MIN_W);
                } else {
                    float codeDesired = codeContentDesired();
                    float traceDesired = 200f;
                    float total = codeDesired + traceDesired;
                    codeW = total <= remain ? codeDesired : Mathf.clamp(remain * codeDesired / total, 200f, remain - TRACE_MIN_W);
                }
                monitorTable.add(editTools).colspan(3).height(80).growX();
                monitorTable.row();
                editPanelCellRef = monitorTable.add(editPanel).width(codeW).growY();
                monitorTable.add(codeTraceSplitter).width(SPLIT_W).growY();
                monitorTable.add(tracePane).grow();
            } else {
                monitorTable.add(editTools).height(80).growX();
                monitorTable.row();
                monitorTable.add(editPanel).grow();
            }
        }
    }

    private void varToolsBuild() {
        varTools.clear();
        varTools.table(t -> {
            t.button(Icon.rotate, Styles.emptyi, () -> {
                if (logicBuild.executor.counter == null) return;
                logicBuild.executor.counter.numval = 0;
                counter = 0;
                if (pause) scrollToCounter();
            }).grow();

            // 重置调试会话: 指令指针归零, 清空流水、光轨与快照
            t.button(Icon.trash, Styles.emptyi, () -> {
                logicBuild.updateCode(logicBuild.code);
                logicBuild.executor.textBuffer.setLength(0);
                if (pause) {
                    resumeInstructions = null;
                    counter = 0;
                    setPaused(true);
                } else {
                    if (logicBuild.executor.counter != null) logicBuild.executor.counter.numval = 0;
                    counter = 0;
                }
                traceHistory.clear();
                traceStepCounter = 0;
                selectedTrace = null;
                highlightedTraceLine = -1;
                Arrays.fill(recentExecutedLines, -1);
                recentLineIndex = 0;
                rebuildVarTable();
                rebuildCodeTable();
                rebuildTraceTable();
                rebuildRollbackBar();
                monitorTableBuild();
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
                    if (size.x < DUAL_MIN_WIDTH) showVarPage = false;
                }
                monitorTableBuild();
            }).grow();
        }).height(40).growX();

        varTools.row();
        varTools.table(t -> {
            // 变量名实时过滤搜索；紧凑布局防止窄屏溢出
            t.field(varFilter, s -> {
                varFilter = s;
                rebuildVarTable();
            }).minWidth(60f).padLeft(6f).growX();
            TextButton buttonCc = t.button(filterCc ? "Cc" : "[grey]Cc", Styles.cleart, () -> {}).size(36f).get();
            buttonCc.clicked(() -> {
                filterCc = !filterCc;
                buttonCc.setText(filterCc ? "Cc" : "[grey]Cc");
                rebuildVarTable();
            });
            TextButton buttonW = t.button(filterW ? "W" : "[grey]W", Styles.cleart, () -> {}).size(36f).get();
            buttonW.clicked(() -> {
                filterW = !filterW;
                buttonW.setText(filterW ? "W" : "[grey]W");
                rebuildVarTable();
            });
            // 全局完整显示开关: 所有变量值不折叠 (展开态行高按历史最大长度预锁, 不闪烁)
            ImageButton.ImageButtonStyle expandStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
            ImageButton expandButton = t.button(allExpanded ? Icon.downSmall : Icon.upSmall, expandStyle, () -> {}).size(36f).get();
            expandButton.clicked(() -> {
                allExpanded = !allExpanded;
                expandStyle.imageUp = allExpanded ? Icon.downSmall : Icon.upSmall;
                expandButton.setStyle(expandStyle);
                rebuildVarTable();
            });
        }).height(40).growX();
    }

    private void editToolsBuild() {
        editTools.clear();
        editTools.table(t -> {
            t.button(Icon.refresh, Styles.emptyi, () -> {
                rebuildCodeTable();
                monitorTableBuild();
            }).grow();
            t.button(Icon.save, Styles.emptyi, this::uploadCode).grow();
            // 避免图标与流水按钮冲突，使用 tree 图标专司单双栏切换
            t.button(Icon.tree, Styles.emptyi, () -> {
                autoCollapsedDual = false;
                if (showVarPage) showEditPage = false;
                else {
                    showVarPage = true;
                    if (size.x < DUAL_MIN_WIDTH) showEditPage = false;
                }
                monitorTableBuild();
            }).grow();
        }).height(40).growX();

        editTools.row();
        editTools.table(t -> {
            ImageButton.ImageButtonStyle pauseStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
            ImageButton pauseButton = t.button(Icon.pause, pauseStyle, () -> {}).grow().get();

            // 单步调试: 运行中自感知暂停，更新计数器并瞬时刷新，流水是否弹出完全由流水按钮主导
            t.button(Icon.left, Styles.emptyi, () -> {
                if (!pause) {
                    pause = true;
                    logicPause();
                }
                stepOnce();
            }).grow();

            // 运行到断点: 运行中自感知暂停，更新计数器并瞬时刷新
            t.button(Icon.undo, Styles.emptyi, () -> {
                if (!pause) {
                    pause = true;
                    logicPause();
                }
                skipToBreakpoint();
            }).grow();

            // 流水模式开关: 纯粹手动主导，原地刷新代码行编辑按键显隐与三栏平铺布局，绝不自动变色
            t.button(Icon.list, Styles.emptyi, () -> {
                showTrace = !showTrace;
                if (!showTrace) {
                    selectedTrace = null;
                    highlightedTraceLine = -1;
                }
                activeEditCell = null;
                codeCells.forEach(CodeCell::codeCellBuild);
                rebuildTraceTable();
                rebuildRollbackBar();
                monitorTableBuild();
            }).grow();

            pauseButton.clicked(() -> {
                pause = !pause;
                if (pause) logicPause();
                else logicRerun();
            });

            pauseButton.update(() -> {
                pauseButton.getStyle().imageUp = pause ? Icon.play : Icon.pause;
            });
        }).height(40).growX();
    }

    private void buildTraceHeader() {
        tracePane.clear();
        Table t = new Table();
        Label hashLbl = new Label("[#" + Yr2Vars.mutedHex + "]#[]", Styles.outlineLabel);
        hashLbl.setEllipsis(true);
        t.add(hashLbl).left().growX().minWidth(0f).padLeft(4f);
        t.button(Icon.copySmall, Styles.emptyi, () -> {
            StringBuilder sb = new StringBuilder("# yr2lm trace\n");
            for (int i = traceHistory.size() - 1; i >= 0; i--) {
                sb.append(traceHistory.get(i).toString()).append("\n");
            }
            Core.app.setClipboardText(sb.toString());
        }).size(28f);
        // 清空流水: 原地瞬间刷新，绝无需缩放触发
        t.button(Icon.trashSmall, Styles.emptyi, () -> {
            traceHistory.clear();
            traceStepCounter = 0;
            selectedTrace = null;
            highlightedTraceLine = -1;
            rebuildTraceTable();
            rebuildRollbackBar();
        }).size(28f);
        tracePane.add(t).height(30f).growX().padBottom(2f);
        tracePane.row();
        tracePane.add(traceScroll).grow();
    }

    /** 重构变量表 (保留 ScrollPane 与视口焦点)。 */
    private void rebuildVarTable() {
        constants.clear();
        links.clear();
        LExecutor exec = logicBuild.executor;
        if (exec.vars != null) {
            varSignature = exec.vars.length ^ (exec.counter == null ? 0 : exec.counter.hashCode());
        }
        for (LVar var : exec.vars) {
            if (!var.constant) continue;
            if (var.name.startsWith("@")) constants.add(var);
            else if (!var.name.startsWith("___")) links.add(var);
        }
        collectBuiltinConstants(exec);

        varTable.clear();
        varTable.top().left();
        int maxNameLen = 4;
        int maxValLen = 4;
        for (LVar var : exec.vars) {
            if (var.constant) continue;
            if (checkVarName(var.name)) {
                maxNameLen = Math.max(maxNameLen, var.name.length());
                String s = formatVarText(var);
                if (s != null) maxValLen = Math.max(maxValLen, s.length());
                varTable.add(new VarCell(var, var.name)).growX();
                varTable.row();
            }
        }
        for (LVar var : constants) {
            if (checkVarName(var.name)) {
                maxNameLen = Math.max(maxNameLen, var.name.length());
                String s = formatVarText(var);
                if (s != null) maxValLen = Math.max(maxValLen, s.length());
                varTable.add(new VarCell(var, var.name)).growX();
                varTable.row();
            }
        }
        if (checkVarName("textBuffer")) {
            maxValLen = Math.max(maxValLen, Math.min(30, logicBuild.executor.textBuffer.length()));
            varTable.table(t -> {
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
            varTable.row();
        }
        int size = links.size();
        for (int i = 0; i < size; i++) {
            LVar var = links.get(i);
            maxNameLen = Math.max(maxNameLen, var.name.length() + 3);
            String s = formatVarText(var);
            if (s != null) maxValLen = Math.max(maxValLen, s.length());
            varTable.add(new VarCell(var, String.format("[%d]%s", i, var.name))).growX();
            varTable.row();
        }

        dynNameCol = Mathf.clamp(maxNameLen * CHAR_W + 16f, 130f, 190f);
        maxValContentLen = maxValLen;
    }

    /** 重构代码表 (保留断点与行列表)。 */
    private void rebuildCodeTable() {
        codeCells.clear();
        codeTable.clear();
        codeTable.top().left();
        Seq<LStatement> codeList = LAssembler.read(logicBuild.code, logicBuild.block.privileged);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codeList.size; i++) {
            sb.setLength(0);
            codeList.get(i).write(sb);
            CodeCell cell = new CodeCell(i, sb.toString());
            codeCells.add(cell);
            codeTable.add(cell).growX();
            codeTable.row();
        }
    }

    /** 重构流水列表 (瞬间呈现最新单步或清空态)。 */
    private void rebuildTraceTable() {
        traceTable.clear();
        traceTable.top().left();
        if (traceHistory.isEmpty()) {
            traceTable.add(new Label("[#" + Yr2Vars.mutedHex + "]---", Styles.outlineLabel)).padTop(20f).center();
            return;
        }
        for (int i = traceHistory.size() - 1; i >= 0; i--) {
            TraceRecord rec = traceHistory.get(i);
            if (rec.isCollapsed) {
                traceTable.table(row -> {
                    row.center().margin(1f, 2f, 1f, 2f);
                    row.add(new Label("[#" + Yr2Vars.mutedHex + "]L" + rec.fromLine + " -> L" + rec.toLine + " (" + rec.collapsedCount + ")")).center();
                    row.clicked(() -> scrollToLine(rec.fromLine));
                }).minHeight(20f).growX();
                traceTable.row();
                continue;
            }

            boolean isCurrent = (selectedTrace == rec || highlightedTraceLine == rec.fromLine);
            traceTable.table(card -> {
                card.left().margin(2f, 4f, 2f, 4f);

                // 头部单行: 步号 + 来源行 + (跳转目标)
                card.table(headRow -> {
                    headRow.left();
                    String prefix = (isCurrent ? "[#" + Yr2Vars.themeHex + "]> []" : "")
                            + "[#" + Yr2Vars.mutedHex + "]#" + rec.step + " [L" + rec.fromLine + "] ";
                    Label prefixLbl = new Label(prefix);
                    prefixLbl.setEllipsis(true);
                    headRow.add(prefixLbl).left().minWidth(0f).growX();

                    if (rec.isJump) {
                        headRow.add(new Label("[#" + Yr2Vars.jumpHex + "]-> L" + rec.toLine)).left().padRight(4f);
                    }
                }).growX().left();
                card.row();

                // 多个变量变动时: 每一个变量独立换行显示，层次分明
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

                card.clicked(() -> {
                    if (selectedTrace == rec) {
                        selectedTrace = null;
                        highlightedTraceLine = -1;
                    } else {
                        selectedTrace = rec;
                        highlightedTraceLine = rec.fromLine;
                    }
                    refreshDebugViews();
                    monitorTableBuild();
                    if (highlightedTraceLine >= 0) {
                        Time.run(2f, () -> scrollToLine(highlightedTraceLine));
                    }
                });
            }).minHeight(24f).growX().padBottom(2f);
            traceTable.row();
        }
    }

    /** 原地重构时间旅行回溯横条。 */
    private void rebuildRollbackBar() {
        traceRollbackBar.clear();
        if (selectedTrace != null) {
            Label rollbackLbl = new Label("[#" + Yr2Vars.themeHex + "]<< [#" + Yr2Vars.mutedHex + "]Step #" + selectedTrace.step + " [L" + selectedTrace.fromLine + "]");
            rollbackLbl.setEllipsis(true);
            traceRollbackBar.add(rollbackLbl).growX().left().minWidth(0f).padLeft(8);
            traceRollbackBar.button(Icon.refreshSmall, Styles.emptyi, () -> {
                selectedTrace = null;
                highlightedTraceLine = -1;
                refreshDebugViews();
                monitorTableBuild();
            }).size(28).padRight(6);
        }
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

    private void uploadCode() {
        logicBuild.updateCode(codeCells.stream()
                .filter(codeCell -> !codeCell.code.isEmpty())
                .map(codeCell -> codeCell.code + "\n")
                .collect(Collectors.joining())
        );
        if (pause) {
            resumeInstructions = null;
            counter = 0;
            setPaused(true);
        }
        breakpoints.removeIf(b -> b >= codeCells.size());
        activeEditCell = null;
        traceHistory.clear();
        traceStepCounter = 0;
        selectedTrace = null;
        highlightedTraceLine = -1;
        Arrays.fill(recentExecutedLines, -1);
        recentLineIndex = 0;
        rebuildCodeTable();
        rebuildTraceTable();
        rebuildRollbackBar();
        monitorTableBuild();
    }

    private void setPaused(boolean p) {
        LExecutor exec = logicBuild.executor;
        if (p) {
            if (exec.instructions != PAUSED) {
                resumeInstructions = exec.instructions;
                exec.instructions = PAUSED;
            }
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

    private void runPaused(Runnable runner) {
        LExecutor exec = logicBuild.executor;
        if (exec.instructions == PAUSED && resumeInstructions != null) exec.instructions = resumeInstructions;
        try {
            runner.run();
        } finally {
            if (pause && exec.instructions != PAUSED) {
                resumeInstructions = exec.instructions;
                exec.instructions = PAUSED;
            }
        }
    }

    /** 单步: 执行一条指令并记录追踪流水。流水是否弹出完全由流水按钮掌控，不越权篡改。 */
    private void stepOnce() {
        selectedTrace = null;
        highlightedTraceLine = -1;
        runPaused(this::executeTrackedStep);
        LExecutor exec = logicBuild.executor;
        if (exec.counter != null) counter = normalizePc((int) exec.counter.numval);
        scrollToCounter();
        refreshDebugViews();
    }

    /** 运行到下一个断点, 手动推进并在环形缓冲区内记录执行轨迹。流水是否弹出完全由流水按钮掌控，不越权篡改。 */
    private void skipToBreakpoint() {
        selectedTrace = null;
        highlightedTraceLine = -1;
        runPaused(() -> {
            LExecutor exec = logicBuild.executor;
            for (int i = 0; i < 10000; i++) {
                executeTrackedStep();
                if (exec.instructions == null || exec.instructions.length == 0) break;
                // end 指令把指针置于越界哨兵, 原版下一轮 runOnce 会归零继续循环 — 此处同样归零后照常检测断点, 绝不提前中断
                int line = exec.counter == null ? -1 : normalizePc((int) exec.counter.numval);
                if (breakpoints.contains(line)) break;
            }
        });
        LExecutor exec = logicBuild.executor;
        if (exec.counter != null) counter = normalizePc((int) exec.counter.numval);
        scrollToCounter();
        refreshDebugViews();
    }

    private void scrollToLine(int line) {
        if (codeCells.isEmpty() || editPanel == null) return;
        int clamped = Mathf.clamp(line, 0, codeCells.size() - 1);
        float percent = (float) clamped / Math.max(1, codeCells.size() - 1);
        editPanel.setScrollPercentY(percent);
    }

    private void scrollToCounter() {
        scrollToLine(counter);
    }

    private void logicPause() {
        LExecutor exec = logicBuild.executor;
        if (exec.counter != null) counter = normalizePc((int) exec.counter.numval);
        setPaused(true);
        scrollToCounter();
        refreshDebugViews();
    }

    private void logicRerun() {
        selectedTrace = null;
        highlightedTraceLine = -1;
        Arrays.fill(recentExecutedLines, -1);
        recentLineIndex = 0;
        setPaused(false);
        refreshDebugViews();
    }

    @Override
    protected void onResized() {
        if (attachedPaneMode) {
            ConfigInjector.paneLogicW = size.x;
            ConfigInjector.paneLogicH = size.y;
        } else {
            ConfigInjector.lastLogicW = size.x;
            ConfigInjector.lastLogicH = size.y;
        }
        if (showVarPage && showEditPage) {
            monitorTableBuild();
        }
    }

    @Override
    public LogicBlock.LogicBuild getBuilding() {
        return logicBuild;
    }
}
