package yr2lm.ui;

import arc.Core;
import arc.scene.ui.layout.Scl;

/**
 * 三栏宽度的唯一算式。
 *
 * 铁律: 变量栏 + 代码栏 + 流水栏 + 分隔线 == 窗口总宽, 恒等。
 * 旧写法把"某一栏不许低于 X"写成 `Math.max(硬下限, 剩余空间)` 之类的兜底, 窗口一窄就让某一栏反过来把整张表
 * 撑得比窗口还宽, 多出来的部分被窗口右边缘裁掉 —— 表现就是"流水栏的复制/删除按钮被挤出去"。
 * 现在最小宽度只是**愿望的下限**, 分配交给同一个算式: 装得下就各取所需, 装不下就按比例一起压, 等式不破。
 *
 * 本类不认识任何具体控件: 每一栏的下限和愿望都由拥有那栏的面板自己报(见 Floors), 算式只做分配。
 * 以前这里另抄了一份控件尺寸清单, 那份抄件会随界面改动无声失效 —— 就是"加了按钮忘了改下限"这类故障的源头。
 *
 * 单位: 本类里所有的数(下限、愿望、返回的栏宽)一律是**设计单位**, 也就是 .width()/.size()/.pad() 传进去的那种数。
 * 窗口自己的 size.x 与任何元素 getMinWidth()/getPrefWidth() 的返回值都是引擎缩放后的实际像素, 两者差一个界面缩放
 * 系数(桌面端就是游戏设置里的那个缩放, 会被用户改, 别当成常量)。当初漏了这一步, 实测整张表比窗口宽出恰好一个
 * 缩放系数, 表现为开窗口就溢出。所以只在"进出窗口"的两个边界换算: 进来用 design(), 出去给窗口用 scene()。
 */
public class ColumnLayout {
    public static final float SPLIT_W = 12f;
    /** 一个字符的近似宽度, 供各面板量自己的内容用(字体度量, 不是某个控件的尺寸). */
    public static final float CHAR_W = 8.5f;

    /** 窗口的实际宽度 → 算式用的设计宽度. */
    public static float design(float sceneUnits) {
        return sceneUnits / Scl.scl();
    }

    /** 算式给出的设计宽度 → 窗口自己的实际尺寸. */
    public static float scene(float designUnits) {
        return designUnits * Scl.scl();
    }

    /**
     * 三个面板一次自报的全部内容(均为设计单位). 除面板自己之外, 谁都不许再留这些数的副本.
     *
     * floor = 该栏"绝不能被裁的那一排"合起来的真实宽度; desired = 该栏内容想要的宽度, 可被压缩。
     */
    public static final class Report {
        public float varFloor, codeFloor, traceFloor;
        public float varDesired, codeDesired, traceDesired;

        public Report(float varFloor, float codeFloor, float traceFloor,
                      float varDesired, float codeDesired, float traceDesired) {
            this.varFloor = varFloor;
            this.codeFloor = codeFloor;
            this.traceFloor = traceFloor;
            this.varDesired = varDesired;
            this.codeDesired = codeDesired;
            this.traceDesired = traceDesired;
        }
    }

    /** 一次分配的结果. 三格之和 + 分隔线 == 传入的总宽. */
    public static final class Widths {
        public float varW, codeW, traceW;
    }

    private final String posKey;
    private float userVarW = -1f;
    private float userCodeW = -1f;
    private boolean varMemLoaded = false;
    private boolean codeMemLoaded = false;
    private float lastTrackedW = -1f;
    /** 上一次分配出来的三栏宽度: 拖拽以它为基准加偏移, 不再自己重算一遍公式. */
    private final Widths last = new Widths();
    /** 上一次分配真正用下的下限: 拖拽要把愿望夹回下限, 但不许再去面板问第二遍(形态可能在途中变了). */
    private float lastVarFloor, lastCodeFloor;

    public ColumnLayout(String posKey) {
        this.posKey = posKey;
    }

    /** 分隔线根数: 只在两栏真的并排时才存在. */
    private static int splitCount(boolean showVar, boolean showEdit, boolean trace) {
        return ((showVar && showEdit) ? 1 : 0) + ((showEdit && trace) ? 1 : 0);
    }

    /** 当前形态下"每一栏内容都不被裁"所需的最小窗口宽, 供窗口自己的最小尺寸引用. */
    public float requiredWidth(boolean showVar, boolean showEdit, boolean showTrace, Report r) {
        boolean trace = showTrace && showEdit;
        float floor = (showVar ? r.varFloor : 0f) + (showEdit ? r.codeFloor : 0f) + (trace ? r.traceFloor : 0f);
        return floor + SPLIT_W * splitCount(showVar, showEdit, trace);
    }

    public void handleResize(float newW) {
        if (lastTrackedW > 1f && newW > 1f && Math.abs(newW - lastTrackedW) > 0.5f) {
            float ratio = newW / lastTrackedW;
            if (userVarW > 0f) userVarW *= ratio;
            if (userCodeW > 0f) userCodeW *= ratio;
        }
        if (newW > 1f) lastTrackedW = newW;
    }

    /**
     * 唯一的宽度分配算式: 三栏之和恒等于"总宽减去分隔线".
     *
     * @param r 三个面板一次自报下来的下限与愿望(本类不认识任何具体控件, 这些数只能由面板报)
     */
    public Widths allocate(float totalW, boolean showVar, boolean showEdit, boolean showTrace, Report r) {
        boolean trace = showTrace && showEdit;
        float body = Math.max(0f, totalW - SPLIT_W * splitCount(showVar, showEdit, trace));
        float varFloor = showVar ? r.varFloor : 0f;
        float codeMin = showEdit ? r.codeFloor : 0f;
        float traceFloor = trace ? r.traceFloor : 0f;
        lastVarFloor = varFloor;
        lastCodeFloor = codeMin;
        float floors = varFloor + codeMin + traceFloor;

        //装不下所有硬下限: 按比例一起压, 谁也别独占, 等式照样守住(窗口最小尺寸已把这种情形挡在常规操作之外)
        if (body <= floors) {
            float k = body / Math.max(1f, floors);
            last.varW = varFloor * k;
            last.codeW = codeMin * k;
            //剩下那一截交给最右一个**显示中**的栏目, 绝不给隐藏的栏 —— 否则浮点残差会让隐藏栏白拿一点宽度
            float rem = body - last.varW - last.codeW;
            last.traceW = 0f;
            if (trace) last.traceW = rem;
            else if (showEdit) last.codeW += rem;
            else last.varW += rem;
            return last;
        }

        loadMemory(totalW);
        float extra = body - floors;
        //关掉的栏目必须一分都不拿: 之前变量栏的"饥渴度"没判它有没有显示, 变量页一关, 那份宽度无人认领, 就成了窗口右边那条空白
        float varHunger = showVar ? Math.max(0f, (userVarW > 0f ? userVarW : r.varDesired) - varFloor) : 0f;
        float codeHunger = showEdit ? Math.max(0f, (userCodeW > 0f ? userCodeW : r.codeDesired) - codeMin) : 0f;
        float traceHunger = trace ? Math.max(0f, r.traceDesired - traceFloor) : 0f;
        float hunger = varHunger + codeHunger + traceHunger;

        if (hunger <= extra) {
            //大家都拿得饱
            last.varW = varFloor + varHunger;
            last.codeW = codeMin + codeHunger;
            last.traceW = traceFloor + traceHunger;
        } else {
            //都还饿: 按各自的饥渴度比例分这一口
            last.varW = varFloor + extra * varHunger / hunger;
            last.codeW = codeMin + extra * codeHunger / hunger;
            last.traceW = traceFloor + extra * traceHunger / hunger;
        }
        //剩下的那一截归最右一栏(定案: 有流水给流水, 没流水给代码)
        float rest = body - last.varW - last.codeW - last.traceW;
        if (trace) last.traceW += rest;
        else if (showEdit) last.codeW += rest;
        else last.varW += rest;
        return last;
    }

    private void loadMemory(float totalW) {
        if (!varMemLoaded && totalW > 1f) {
            varMemLoaded = true;
            float saved = Core.settings.getFloat(posKey + ".varRatio", 0f);
            if (saved > 0f && userVarW < 0f) userVarW = saved * totalW;
        }
        if (!codeMemLoaded && totalW > 1f) {
            codeMemLoaded = true;
            float saved = Core.settings.getFloat(posKey + ".codeRatio", 0f);
            if (saved > 0f && userCodeW < 0f) userCodeW = saved * totalW;
        }
    }

    /** 拖分栏只改"愿望", 怎么落地交给同一个 allocate(): 拖拽与自动初值不可能再算出两套宽度. */
    public void dragVar(float dx, float totalW, boolean showVar, boolean showEdit, boolean showTrace, Report r) {
        allocate(totalW, showVar, showEdit, showTrace, r);
        userVarW = Math.max(lastVarFloor, last.varW + dx);
    }

    public void dragCode(float dx, float totalW, boolean showVar, boolean showEdit, boolean showTrace, Report r) {
        allocate(totalW, showVar, showEdit, showTrace, r);
        userCodeW = Math.max(lastCodeFloor, last.codeW + dx);
    }

    public void resetVar() {
        userVarW = -1f;
        varMemLoaded = true;
        Core.settings.put(posKey + ".varRatio", 0f);
    }

    public void resetCode() {
        userCodeW = -1f;
        codeMemLoaded = true;
        Core.settings.put(posKey + ".codeRatio", 0f);
    }

    public void saveVarRatio(float totalW) {
        if (userVarW > 0f) Core.settings.put(posKey + ".varRatio", userVarW / Math.max(1f, totalW));
    }

    public void saveCodeRatio(float totalW) {
        if (userCodeW > 0f) Core.settings.put(posKey + ".codeRatio", userCodeW / Math.max(1f, totalW));
    }
}
