package yr2lm.ui.logic;

import arc.Core;
import arc.math.Mathf;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Yrailiuxa2;
import yr2lm.util.MemUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * 逻辑块变量监视面板。
 */
public class VarPanel {
    static final float SEARCH_MIN_W = 45f;
    static final float TOOL_BTN = 34f;
    static final float TOOLS_PAD_L = 6f;

    public final Table tools;
    public final Table table;
    public final ScrollPane scroll;

    private final LogicBlock.LogicBuild logicBuild;
    private final DebugExecutor executor;
    private final TraceTimeline timeline;
    private final Runnable onResetCounter;
    private final Runnable onResetSession;
    private final Runnable onToggleEditorPage;

    final ArrayList<LVar> constants = new ArrayList<>();
    final ArrayList<LVar> links = new ArrayList<>();
    final ArrayList<LVar> rowVars = new ArrayList<>();
    final ArrayList<String> rowNames = new ArrayList<>();
    final ArrayList<VarCell> rowCells = new ArrayList<>();
    int textBufferAt = -1;
    private LVar[] watchedVars;
    private String lastScannedCode = null;
    private final LinkedHashSet<String> cachedCodeConstNames = new LinkedHashSet<>();

    String varFilter = "";
    boolean filterCc = false, filterW = false;
    boolean drawAllVars = false;
    float dynNameCol = 130f;
    final ObjectMap<String, Boolean> manualExpandedVars = new ObjectMap<>();
    boolean allExpanded = false;
    private String editingVarName = null;

    @SuppressWarnings("this-escape")
    public VarPanel(LogicBlock.LogicBuild logicBuild,
                    DebugExecutor executor,
                    TraceTimeline timeline,
                    Runnable onResetCounter,
                    Runnable onResetSession,
                    Runnable onToggleEditorPage) {
        this.logicBuild = logicBuild;
        this.executor = executor;
        this.timeline = timeline;
        this.onResetCounter = onResetCounter;
        this.onResetSession = onResetSession;
        this.onToggleEditorPage = onToggleEditorPage;

        tools = new Table();
        table = new Table();
        table.top().left();
        scroll = new ScrollPane(table);
        Yrailiuxa2.configurePane(scroll);
        scroll.update(() -> Yr2Vars.bindScrollFocus(scroll));
        buildTools();
        rebuild();
    }

    public float getDynNameCol() {
        return dynNameCol;
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

    public boolean isDrawAllVars() {
        return drawAllVars;
    }

    public boolean isAllExpanded() {
        return allExpanded;
    }

    public boolean isManualExpanded(String rawName) {
        return manualExpandedVars.get(rawName, false);
    }

    public void toggleManualExpanded(String rawName) {
        if (isManualExpanded(rawName)) manualExpandedVars.remove(rawName);
        else manualExpandedVars.put(rawName, true);
    }

    public String getEditingVarName() {
        return editingVarName;
    }

    public void setEditingVarName(String name) {
        this.editingVarName = name;
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
                if (onResetCounter != null) onResetCounter.run();
            }).minWidth(0f).grow();

            t.button(Icon.trash, Styles.emptyi, () -> {
                if (onResetSession != null) onResetSession.run();
            }).minWidth(0f).grow();

            ImageButton drawButton = t.button(Icon.eyeOffSmall, Styles.emptyi, () -> drawAllVars = !drawAllVars).minWidth(0f).grow().get();
            drawButton.update(() -> drawButton.getStyle().imageUp = drawAllVars ? Icon.eyeSmall : Icon.eyeOffSmall);

            t.button(Icon.edit, Styles.emptyi, () -> {
                if (onToggleEditorPage != null) onToggleEditorPage.run();
            }).minWidth(0f).grow();
        }).height(40).minWidth(0f).growX();

        tools.row();
        tools.table(t -> {
            TextField searchField = t.field(varFilter, s -> {
                varFilter = s;
                rebuild();
            }).minWidth(SEARCH_MIN_W).padLeft(TOOLS_PAD_L).growX().get();

            ImageButton clearSearchBtn = t.button(Icon.cancelSmall, Styles.emptyi, () -> {
                varFilter = "";
                searchField.setText("");
                rebuild();
            }).size(24f).get();
            clearSearchBtn.visible(() -> !varFilter.isEmpty());

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

            ImageButton expandButton = t.button(allExpanded ? Icon.downSmall : Icon.upSmall, Styles.emptyi, () -> {
                allExpanded = !allExpanded;
                if (!allExpanded) manualExpandedVars.clear();
                rebuild();
            }).size(TOOL_BTN).get();
            expandButton.update(() -> expandButton.getStyle().imageUp = allExpanded ? Icon.downSmall : Icon.upSmall);
        }).height(40).minWidth(0f).growX();
    }

    public void rebuild() {
        collectRows();
        buildRows();
    }

    public void syncVars() {
        LVar[] vars = logicBuild.executor.vars;
        if (vars == null || vars == watchedVars) return;
        rebuild();
    }

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
        for (LVar var : exec.vars) {
            if (var.constant) continue;
            if (!checkVarName(var.name)) continue;
            maxNameLen = Math.max(maxNameLen, var.name.length());
            rowVars.add(var);
            rowNames.add(var.name);
        }
        for (LVar var : constants) {
            if (!checkVarName(var.name)) continue;
            maxNameLen = Math.max(maxNameLen, var.name.length());
            rowVars.add(var);
            rowNames.add(var.name);
        }
        if (checkVarName("textBuffer")) {
            textBufferAt = rowVars.size();
        }
        for (int i = 0, size = links.size(); i < size; i++) {
            LVar var = links.get(i);
            maxNameLen = Math.max(maxNameLen, var.name.length() + 3);
            rowVars.add(var);
            rowNames.add(String.format("[%d]%s", i, var.name));
        }

        dynNameCol = Mathf.clamp(maxNameLen * ColumnLayout.CHAR_W + 16f, 130f, 190f);
    }

    private void buildRows() {
        ObjectMap<String, VarCell> pool = new ObjectMap<>();
        for (VarCell cell : rowCells) pool.put(cell.rawName, cell);
        rowCells.clear();

        table.clearChildren();
        table.top().left();
        for (int i = 0; i < rowVars.size(); i++) {
            if (i == textBufferAt) addTextBufferRow();
            String name = rowNames.get(i);
            LVar var = rowVars.get(i);
            VarCell cell = pool.get(name);
            if (cell == null) {
                cell = new VarCell(this, var, name);
            } else {
                cell.var = var;
                cell.invalidateValueCache();
                cell.rebuildCell();
            }
            rowCells.add(cell);
            // 将 pad(0, 8, 0, 8) 放在父表的 Cell 上，与拆包前布局完全一致
            table.add(cell).minHeight(32f).growX().pad(0f, 8f, 0f, 8f);
            table.row();
        }
        if (textBufferAt >= rowVars.size()) addTextBufferRow();
    }

    private void addTextBufferRow() {
        table.table(t -> {
            Label nameLbl = new Label("textBuffer");
            nameLbl.setWrap(true);
            nameLbl.setAlignment(Align.left);
            t.add(nameLbl).left().width(dynNameCol).growY().padRight(8f);

            Label valLbl = new Label(new arc.func.Prov<>() {
                int lastLen = -1;
                int lastFirstChar = -1;
                String cached = "0 | \"\"";

                @Override
                public CharSequence get() {
                    StringBuilder tb = logicBuild.executor.textBuffer;
                    int len = tb.length();
                    int first = len > 0 ? tb.charAt(0) : -1;
                    if (len != lastLen || first != lastFirstChar) {
                        lastLen = len;
                        lastFirstChar = first;
                        cached = len + " | \"" + tb.toString().replace('\n', ' ') + "\"";
                    }
                    return cached;
                }
            });
            valLbl.setEllipsis(true);
            valLbl.setAlignment(Align.left);
            t.add(valLbl).left().minWidth(0f).growX().growY();

            t.clicked(() -> Core.app.setClipboardText(logicBuild.executor.textBuffer.toString()));
        }).height(32f).growX().pad(0f, 8f, 0f, 8f);
        table.row();
    }

    private void collectBuiltinConstants(LExecutor exec) {
        addConstant(exec.unit);
        addConstant(exec.thisv);
        addConstant(exec.ipt);
        addConstant(exec.queryResult);
        boolean privileged = logicBuild.block.privileged;
        String code = logicBuild.code == null ? "" : logicBuild.code;
        if (!code.equals(lastScannedCode)) {
            lastScannedCode = code;
            cachedCodeConstNames.clear();
            extractCodeConstants(code, cachedCodeConstNames);
        }
        for (String name : cachedCodeConstNames) {
            if (hasVar(exec, name)) continue;
            LVar var = Vars.logicVars.get(name, privileged);
            addConstant(var);
        }
    }

    private static void extractCodeConstants(String code, LinkedHashSet<String> out) {
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '#') {
                while (i < n && code.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    char sc = code.charAt(i);
                    if (sc == '\\' && i + 1 < n) { i += 2; continue; }
                    if (sc == '"' || sc == '\n') { i++; break; }
                    i++;
                }
                continue;
            }
            if (c == '@' && i + 1 < n && isIdentStart(code.charAt(i + 1))) {
                int start = i;
                i += 2;
                while (i < n && isIdentPart(code.charAt(i))) i++;
                out.add(code.substring(start, i));
                continue;
            }
            i++;
        }
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '-';
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

    public static void applyVarValue(LVar var, String input) {
        if (var == null || (var.constant && !var.name.equals("@counter"))) return;
        Object parsed = MemUtil.parseValue(input);
        if (parsed instanceof Number num) {
            var.isobj = false;
            var.numval = num.doubleValue();
            var.objval = null;
        } else {
            var.isobj = true;
            var.objval = parsed;
        }
    }

    public String formatVarText(LVar var, int currentCounter) {
        if (var.isobj) {
            if (var.objval instanceof String) return "\"" + var.objval + "\"";
            if (var.objval == null) return "[lightgray]null[]";
            if (var.objval instanceof Unit unit)
                return unit.type.name + "#" + unit.id + " [" + MemUtil.doubleToString(unit.flag) + "]";
            if (var.objval instanceof Building b)
                return b.block.name + "#" + b.id;
            return String.valueOf(var.objval);
        }
        if (Double.isNaN(var.numval) && var.name.equals("@counter")) return "(" + currentCounter + ")";
        return MemUtil.doubleToString(var.numval);
    }
}
