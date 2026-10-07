package yr2lm.util;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 代码区的唯一数据源: 行文本、写回处理器时的原始文本、断点登记都只存在这里。
 * 界面上的每一行只是它当前下标处的投影, 增删行只动本对象, 绝不回头重读处理器,
 * 因此玩家尚未保存的编辑不会被结构的增删抹掉。
 */
public class ScriptModel {
    /** 一行脚本。text 可改, origin 是本会话写回处理器时的样子, 两者不等或本行系新插入即"有改动没保存"。 */
    public static final class Row {
        public String text;
        public final String origin;
        /** true = 本次会话插入、尚未写回处理器。只用于行号处的 "+" 标记。 */
        public final boolean fresh;

        Row(String text, boolean fresh) {
            this.text = text;
            this.origin = text;
            this.fresh = fresh;
        }

        public boolean unsaved() {
            return fresh || !text.equals(origin);
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private final HashSet<Integer> breakpoints = new HashSet<>();

    /**
     * 唯一的重读入口: 从处理器已保存的脚本文本重建全部行。
     * 只有"手动重新拉取"和"点保存"两处该用它 —— 它会丢弃界面上尚未写回的编辑。
     */
    public void loadFrom(String code, boolean privileged) {
        rows.clear();
        Seq<LStatement> stmts = LAssembler.read(code, privileged);
        StringBuilder sb = new StringBuilder();
        for (LStatement stmt : stmts) {
            sb.setLength(0);
            stmt.write(sb);
            rows.add(new Row(sb.toString(), false));
        }
        breakpoints.removeIf(b -> b >= rows.size());
    }

    /**
     * 把一段脚本切成待插入的行。片段里的跳转目的地是相对片段自己的, 按插入位置整体平移。
     */
    public static List<Row> parseSnippet(String snippet, int at, boolean privileged) {
        if (snippet == null) return Collections.emptyList();
        String normalized = snippet.replace("\r\n", "\n");
        Seq<LStatement> stmts = LAssembler.read(normalized, privileged);
        ArrayList<Row> out = new ArrayList<>(stmts.size);
        StringBuilder sb = new StringBuilder();
        for (LStatement stmt : stmts) {
            if (stmt instanceof LStatements.JumpStatement jump) jump.destIndex += at;
            sb.setLength(0);
            stmt.write(sb);
            out.add(new Row(sb.toString(), true));
        }
        return out;
    }

    public static Row blankRow() {
        return new Row("", true);
    }

    public int size() {
        return rows.size();
    }

    public Row get(int i) {
        return rows.get(i);
    }

    public String textAt(int i) {
        return (i >= 0 && i < rows.size()) ? rows.get(i).text : "";
    }

    public int maxTextLength() {
        int max = 6;
        for (Row r : rows) max = Math.max(max, r.text.length());
        return max;
    }

    public boolean isBreakpoint(int i) {
        return breakpoints.contains(i);
    }

    public void toggleBreakpoint(int i) {
        if (!breakpoints.remove(i)) breakpoints.add(i);
    }

    /** 在 at 处插入若干行。 */
    public void insert(int at, List<Row> incoming) {
        if (incoming.isEmpty()) return;
        int index = clampIndex(at);
        rows.addAll(index, incoming);
        shiftBreakpoints(index, incoming.size());
        shiftJumps(index, incoming.size());
    }

    /** 删掉第 at 行, 返回被删的行。指向被删行的跳转目的地留在原地, 那是程序自身的语义问题, 界面不擅自改写。 */
    public Row remove(int at) {
        if (at < 0 || at >= rows.size()) return null;
        Row removed = rows.remove(at);
        shiftBreakpoints(at, -1);
        shiftJumps(at + 1, -1);
        return removed;
    }

    /** 断点登记的是行号, 增删行之后必须整体平移, 否则断点会悄悄跑到别的行上。 */
    private void shiftBreakpoints(int at, int delta) {
        if (breakpoints.isEmpty() || delta == 0) return;
        HashSet<Integer> moved = new HashSet<>();
        for (int b : breakpoints) {
            if (b < at) moved.add(b);
            else if (delta > 0) moved.add(b + delta);
            else if (b > at) moved.add(b - 1);
        }
        breakpoints.clear();
        breakpoints.addAll(moved);
    }

    /** 行号变了, 代码文本里的跳转目的地也得跟着改, 否则程序会跳到错误的行上去。 */
    private void shiftJumps(int from, int delta) {
        if (delta == 0) return;
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            Seq<LStatement> stmts = LAssembler.read(r.text, true);
            //空行与解析不出内容的行必须安全跳过: 直接取第一条语句会在空行上抛越界
            if (stmts.isEmpty()) continue;
            LStatement ls = stmts.get(0);
            if (ls instanceof LStatements.JumpStatement jump && jump.destIndex >= from) {
                jump.destIndex += delta;
                sb.setLength(0);
                ls.write(sb);
                r.text = sb.toString();
            }
        }
    }

    /** 拼出写回处理器的脚本文本: 空行不占指令位, 与游戏原生编辑器的产物保持一致。 */
    public String toCode() {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            if (r.text.isEmpty()) continue;
            sb.append(r.text).append('\n');
        }
        return sb.toString();
    }

    private int clampIndex(int at) {
        return Math.max(0, Math.min(at, rows.size()));
    }
}
