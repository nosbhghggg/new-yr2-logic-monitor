package yr2lm.ui.logic;

import arc.struct.ObjectMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * 流水时序数据体: 单步执行历史、快照回溯与跳行目标查询。
 */
public class TraceTimeline {

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

    public static final int CAPACITY = 64;
    private final ArrayDeque<Record> ring = new ArrayDeque<>(CAPACITY + 1);
    private final ArrayList<Record> historyView = new ArrayList<>(CAPACITY);
    private boolean viewDirty = false;
    private int stepCounter = 0;
    private Record selected = null;
    private int highlightedLine = -1;

    public void recordStep(int fromLine, int toLine, boolean isJump, String code, String diff, ArrayList<String> diffList, HashSet<String> changedVarNames, ObjectMap<String, String> snapshot) {
        boolean isKeyMilestone = isJump || (diff != null && !diff.isEmpty());
        if (!isKeyMilestone) {
            Record last = ring.peekLast();
            if (last != null && last.isCollapsed) {
                last.toLine = toLine;
                last.collapsedCount++;
                return;
            }
            stepCounter++;
            Record collapsed = new Record(stepCounter, fromLine, toLine, false, true, 1, code, "", null, null, snapshot);
            if (ring.size() >= CAPACITY) ring.pollFirst();
            ring.addLast(collapsed);
            viewDirty = true;
            return;
        }

        stepCounter++;
        Record rec = new Record(stepCounter, fromLine, toLine, isJump, false, 1, code, diff, diffList, changedVarNames, snapshot);
        if (ring.size() >= CAPACITY) ring.pollFirst();
        ring.addLast(rec);
        viewDirty = true;
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
        ring.clear();
        historyView.clear();
        viewDirty = false;
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
        if (viewDirty) {
            historyView.clear();
            historyView.addAll(ring);
            viewDirty = false;
        }
        return historyView;
    }
}
