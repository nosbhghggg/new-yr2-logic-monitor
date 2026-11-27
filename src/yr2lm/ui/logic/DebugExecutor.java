package yr2lm.ui.logic;

import arc.func.Func;
import arc.func.Intp;
import arc.func.Prov;
import arc.struct.ObjectMap;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LExecutor.WaitI;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock;
import yr2lm.util.MemUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 逻辑块单步与断点调试执行引擎。
 * <p>
 * 核心特性:
 * 1. 断点仅在手动触发【运行至下一个断点 (`skipToBreakpoint`)】时生效，不在逻辑块正常运行态下强行拦截暂停，保持原版流畅调试节奏；
 * 2. 单步运行 (`stepOnce`) 与跳至下一断点 (`skipToBreakpoint`) 时，遇到 `WaitI` (`wait` 指令) 直接跨过等待计时一步到位。
 */
public class DebugExecutor {
    private static final LInstruction[] PAUSED = new LInstruction[0];
    private static final int MAX_SKIP_STEPS = 10_000;

    private final LogicBlock.LogicBuild logicBuild;
    private final TraceTimeline timeline;
    private final Intp codeCountProvider;
    private final Func<Integer, String> codeAtProvider;
    private final IntPredicate breakpointChecker;
    private final Prov<ObjectMap<String, String>> snapshotCapturer;

    private boolean paused = false;
    private int counter = 0;
    private LInstruction[] resumeInstructions = null;

    private final int[] recentExecutedLines = new int[]{-1, -1, -1, -1, -1, -1, -1, -1};
    private int recentLineIndex = 0;

    private Object[] oldObjs = new Object[0];
    private double[] oldNums = new double[0];
    private boolean[] oldIsObj = new boolean[0];

    public DebugExecutor(LogicBlock.LogicBuild logicBuild,
                         TraceTimeline timeline,
                         Intp codeCountProvider,
                         Func<Integer, String> codeAtProvider,
                         IntPredicate breakpointChecker,
                         Prov<ObjectMap<String, String>> snapshotCapturer) {
        this.logicBuild = logicBuild;
        this.timeline = timeline;
        this.codeCountProvider = codeCountProvider;
        this.codeAtProvider = codeAtProvider;
        this.breakpointChecker = breakpointChecker;
        this.snapshotCapturer = snapshotCapturer;
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

    /**
     * 暂停态下的每帧守护：确保在暂停期间若外部重载了指令数组，依然保持冻结状态且不累计时间片。
     * 正常运行时绝不主动拦截断点，断点仅由玩家点击【运行至下一断点】触发。
     */
    public void tickMaintenance() {
        if (!paused) return;
        LExecutor exec = logicBuild.executor;
        logicBuild.accumulator = 0f;
        if (exec.instructions != PAUSED && exec.instructions != null && exec.instructions.length > 0) {
            resumeInstructions = exec.instructions;
            exec.instructions = PAUSED;
        }
    }

    public void setPaused(boolean p, int codeCount) {
        LExecutor exec = logicBuild.executor;
        this.paused = p;
        logicBuild.accumulator = 0f;
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
                    try {
                        LAssembler asm = LAssembler.assemble(logicBuild.code, logicBuild.block.privileged);
                        exec.load(asm);
                    } catch (RuntimeException ignored) {}
                }
            }
        }
    }

    public void runPaused(Runnable runner) {
        LExecutor exec = logicBuild.executor;
        logicBuild.accumulator = 0f;
        if (exec.instructions == PAUSED && resumeInstructions != null) exec.instructions = resumeInstructions;
        try {
            runner.run();
        } finally {
            logicBuild.accumulator = 0f;
            if (paused && exec.instructions != PAUSED) {
                resumeInstructions = exec.instructions;
                exec.instructions = PAUSED;
            }
        }
    }

    public void stepOnce() {
        timeline.restoreRealtime();
        runPaused(() -> executeTrackedStep(true));
        LExecutor exec = logicBuild.executor;
        if (exec.counter != null) counter = normalizePc((int) exec.counter.numval, codeCountProvider.get());
    }

    public void skipToBreakpoint() {
        timeline.restoreRealtime();
        runPaused(() -> {
            LExecutor exec = logicBuild.executor;
            int codeCount = codeCountProvider.get();
            for (int i = 0; i < MAX_SKIP_STEPS; i++) {
                boolean captureFullSnapshot = (i < TraceTimeline.CAPACITY) || (i >= MAX_SKIP_STEPS - TraceTimeline.CAPACITY);
                executeTrackedStep(captureFullSnapshot);
                if (exec.instructions == null || exec.instructions.length == 0) break;
                int line = exec.counter == null ? -1 : normalizePc((int) exec.counter.numval, codeCount);
                if (breakpointChecker.test(line)) {
                    if (!captureFullSnapshot) {
                        ArrayList<TraceTimeline.Record> hist = timeline.getHistory();
                        if (!hist.isEmpty()) {
                            TraceTimeline.Record last = hist.get(hist.size() - 1);
                            if (last.varSnapshot != null && last.varSnapshot.isEmpty()) {
                                last.varSnapshot.putAll(snapshotCapturer.get());
                            }
                        }
                    }
                    break;
                }
            }
        });
        LExecutor exec = logicBuild.executor;
        if (exec.counter != null) counter = normalizePc((int) exec.counter.numval, codeCountProvider.get());
    }

    private void ensureScratchCapacity(int n) {
        if (oldObjs.length < n) {
            oldObjs = new Object[n];
            oldNums = new double[n];
            oldIsObj = new boolean[n];
        }
    }

    private void executeTrackedStep(boolean fullSnapshot) {
        LExecutor exec = logicBuild.executor;
        if (exec.instructions == null || exec.instructions.length == 0) return;

        int codeCount = codeCountProvider.get();
        int fromLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval, codeCount);
        String lineCode = (fromLine >= 0 && fromLine < codeCount) ? codeAtProvider.get(fromLine) : "";

        // 单步或跳至断点时，若当前即将执行的指令是 WaitI (wait 指令)，直接满足其等待时长一步跨过，不卡在原地
        int instIdx = (exec.counter == null || exec.counter.numval >= exec.instructions.length || exec.counter.numval < 0)
                ? 0
                : (int) exec.counter.numval;
        if (instIdx < exec.instructions.length && exec.instructions[instIdx] instanceof WaitI wait) {
            wait.curTime = (float) Math.max(0.0, wait.value.num());
        }

        LVar[] vars = exec.vars;
        int n = vars == null ? 0 : vars.length;
        ensureScratchCapacity(n);
        if (vars != null) {
            for (int i = 0; i < n; i++) {
                oldObjs[i] = vars[i].objval;
                oldNums[i] = vars[i].numval;
                oldIsObj[i] = vars[i].isobj;
            }
        }

        exec.runOnce();
        exec.yield = false;

        int toLine = exec.counter == null ? 0 : normalizePc((int) exec.counter.numval, codeCount);
        boolean jumped = (toLine != fromLine + 1);
        counter = toLine;

        ArrayList<String> diffList = null;
        HashSet<String> changedVars = null;
        if (vars != null) {
            for (int i = 0; i < n; i++) {
                if (vars[i].name.equals("@counter")) continue;
                if (vars[i].isobj != oldIsObj[i] || (vars[i].isobj ? !Objects.equals(vars[i].objval, oldObjs[i]) : vars[i].numval != oldNums[i])) {
                    if (diffList == null) {
                        diffList = new ArrayList<>(4);
                        changedVars = new HashSet<>(4);
                    }
                    String oldValStr = oldIsObj[i] ? MemUtil.textOf(oldObjs[i]) : MemUtil.doubleToString(oldNums[i]);
                    String newValStr = vars[i].isobj ? MemUtil.textOf(vars[i].objval) : MemUtil.doubleToString(vars[i].numval);
                    diffList.add(vars[i].name + ": " + oldValStr + " -> " + newValStr);
                    changedVars.add(vars[i].name);
                }
            }
        }
        String diff = (diffList == null || diffList.isEmpty()) ? "" : String.join(", ", diffList);

        recordExecution(fromLine);

        boolean isMilestone = jumped || !diff.isEmpty();
        ObjectMap<String, String> snapshot = (fullSnapshot && isMilestone) ? snapshotCapturer.get() : new ObjectMap<>();
        timeline.recordStep(fromLine, toLine, jumped, lineCode, diff, diffList, changedVars, snapshot);
    }

    public void resetSession() {
        MemUtil.commitLogicCode(logicBuild, logicBuild.code);
        logicBuild.executor.textBuffer.setLength(0);
        logicBuild.accumulator = 0f;
        if (paused) {
            resumeInstructions = null;
            counter = 0;
            setPaused(true, codeCountProvider.get());
        } else {
            if (logicBuild.executor.counter != null) logicBuild.executor.counter.numval = 0;
            counter = 0;
        }
        timeline.clear();
        clearHeat();
    }
}
