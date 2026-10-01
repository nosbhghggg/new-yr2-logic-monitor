package yr2lm.ui;

import arc.struct.ObjectMap;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * 流水时序数据体: 单步执行历史、快照回溯与跳行目标查询。
 * <p>
 * 纯数据与历史, 不碰任何界面控件——原为 LogicMonitor 的静态嵌套类, 拆出独立成文件供各界复用。
 */
public class TraceTimeline {

    /** 单步时序历史记录与快照实体。 */
    public static class Record {
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

        public Record(int step, int fromLine, int toLine, boolean isJump, boolean isCollapsed, int collapsedCount, String code, String diff, ArrayList<String> diffList, HashSet<String> changedVarNames, ObjectMap<String, String> varSnapshot) {
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

    private static final int CAPACITY = 64;
    private final ArrayList<Record> history = new ArrayList<>();
    private int stepCounter = 0;
    private Record selected = null;
    private int highlightedLine = -1;

    public void recordStep(int fromLine, int toLine, boolean isJump, String code, String diff, ArrayList<String> diffList, HashSet<String> changedVarNames, ObjectMap<String, String> snapshot) {
        boolean isKeyMilestone = isJump || !diff.isEmpty();
        if (!isKeyMilestone) {
            if (!history.isEmpty()) {
                Record last = history.get(history.size() - 1);
                if (last.isCollapsed) {
                    last.toLine = toLine;
                    last.collapsedCount++;
                    return;
                }
            }
            stepCounter++;
            Record collapsed = new Record(stepCounter, fromLine, toLine, false, true, 1, code, "", null, null, snapshot);
            if (history.size() >= CAPACITY) history.remove(0);
            history.add(collapsed);
            return;
        }

        stepCounter++;
        Record rec = new Record(stepCounter, fromLine, toLine, isJump, false, 1, code, diff, diffList, changedVarNames, snapshot);
        if (history.size() >= CAPACITY) history.remove(0);
        history.add(rec);
    }

    public void rollbackTo(Record record) {
        selected = record;
        highlightedLine = (record != null ? record.fromLine : -1);
    }

    public void restoreRealtime() {
        selected = null;
        highlightedLine = -1;
    }

    public void clear() {
        history.clear();
        stepCounter = 0;
        restoreRealtime();
    }

    public boolean isSnapshotActive() {
        return selected != null;
    }

    public Record getSelected() {
        return selected;
    }

    public int getHighlightedLine() {
        return highlightedLine;
    }

    public boolean isHighlighted(int line) {
        return highlightedLine == line;
    }

    public boolean isVarChanged(String varName) {
        return selected != null && selected.changedVarNames != null && selected.changedVarNames.contains(varName);
    }

    public String getSnapshotValue(String varName) {
        return (selected != null && selected.varSnapshot != null) ? selected.varSnapshot.get(varName) : null;
    }

    public ArrayList<Record> getHistory() {
        return history;
    }
}
