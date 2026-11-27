package yr2lm.ui.logic;

import arc.Core;
import arc.scene.ui.layout.Scl;
import arc.struct.ObjectMap;

/**
 * 三栏宽度直接比例分配器 (彻底废除第一代“根据文本长度动态计算饥渴度”的反人类牵制设计)。
 */
public class ColumnLayout {
    public static final float SPLIT_W = 10f;
    public static final float CHAR_W = 8.5f;

    /** 各栏紧凑安全下限 (设计单位)。 */
    public static final float MIN_VAR_W = 150f;
    public static final float MIN_CODE_W = 165f;
    public static final float MIN_TRACE_W = 120f;

    private static final String GLOBAL_VAR_KEY = "yr2lm.split.default.varRatio";
    private static final String GLOBAL_TRACE_KEY = "yr2lm.split.default.traceRatio";
    private static final ObjectMap<String, float[]> sessionRatios = new ObjectMap<>();

    public static float design(float sceneUnits) {
        return sceneUnits / Scl.scl();
    }

    public static float scene(float designUnits) {
        return designUnits * Scl.scl();
    }

    public static final class Widths {
        public float varW, codeW, traceW;
    }

    private final String posKey;
    private float varRatio = -1f;
    private float traceRatio = -1f;
    private boolean loaded = false;
    private final Widths last = new Widths();

    public ColumnLayout(String posKey) {
        this.posKey = posKey;
    }

    private static int splitCount(boolean showVar, boolean showEdit, boolean trace) {
        return ((showVar && showEdit) ? 1 : 0) + ((showEdit && trace) ? 1 : 0);
    }

    public float requiredWidth(boolean showVar, boolean showEdit, boolean showTrace) {
        boolean trace = showTrace && showEdit;
        float floor = (showVar ? MIN_VAR_W : 0f) + (showEdit ? MIN_CODE_W : 0f) + (trace ? MIN_TRACE_W : 0f);
        return floor + SPLIT_W * splitCount(showVar, showEdit, trace);
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        float[] session = sessionRatios.get(posKey);
        if (session != null) {
            if (session[0] > 0f) varRatio = session[0];
            if (session[1] > 0f) traceRatio = session[1];
        }
        if (varRatio <= 0f) {
            float savedVar = Core.settings.getFloat(GLOBAL_VAR_KEY, -1f);
            if (savedVar > 0.05f && savedVar < 0.90f) varRatio = savedVar;
        }
        if (traceRatio <= 0f) {
            float savedTrace = Core.settings.getFloat(GLOBAL_TRACE_KEY, -1f);
            if (savedTrace > 0.05f && savedTrace < 0.80f) traceRatio = savedTrace;
        }
    }

    public Widths allocate(float totalW, boolean showVar, boolean showEdit, boolean showTrace) {
        ensureLoaded();
        boolean trace = showTrace && showEdit;
        float body = Math.max(1f, totalW - SPLIT_W * splitCount(showVar, showEdit, trace));

        if (showVar && !showEdit) {
            last.varW = body;
            last.codeW = 0f;
            last.traceW = 0f;
            return last;
        }
        if (!showVar) {
            last.varW = 0f;
            if (!trace) {
                last.codeW = body;
                last.traceW = 0f;
                return last;
            }
            float tr = (traceRatio > 0f) ? traceRatio : 0.30f;
            float tw = clampSection(body * tr, MIN_TRACE_W, body - MIN_CODE_W);
            last.traceW = tw;
            last.codeW = body - tw;
            return last;
        }
        if (!trace) {
            float vr = (varRatio > 0f) ? varRatio : 0.42f;
            float vw = clampSection(body * vr, MIN_VAR_W, body - MIN_CODE_W);
            last.varW = vw;
            last.codeW = body - vw;
            last.traceW = 0f;
            return last;
        }

        float vr = (varRatio > 0f) ? varRatio : 0.34f;
        float tr = (traceRatio > 0f) ? traceRatio : 0.22f;
        float floors = MIN_VAR_W + MIN_CODE_W + MIN_TRACE_W;
        if (body <= floors) {
            float k = body / floors;
            last.varW = MIN_VAR_W * k;
            last.codeW = MIN_CODE_W * k;
            last.traceW = body - last.varW - last.codeW;
            return last;
        }

        float vw = clampSection(body * vr, MIN_VAR_W, body - MIN_CODE_W - MIN_TRACE_W);
        float tw = clampSection(body * tr, MIN_TRACE_W, body - vw - MIN_CODE_W);
        last.varW = vw;
        last.traceW = tw;
        last.codeW = body - vw - tw;
        return last;
    }

    private static float clampSection(float val, float min, float max) {
        if (max <= min) return min;
        return Math.max(min, Math.min(max, val));
    }

    public void dragVar(float dx, float totalW, boolean showVar, boolean showEdit, boolean showTrace) {
        allocate(totalW, showVar, showEdit, showTrace);
        boolean trace = showTrace && showEdit;
        float body = Math.max(1f, totalW - SPLIT_W * splitCount(showVar, showEdit, trace));
        float maxVar = body - last.traceW - (showEdit ? MIN_CODE_W : 0f);
        float newVarW = clampSection(last.varW + dx, MIN_VAR_W, maxVar);
        varRatio = newVarW / body;
    }

    public void dragCode(float dx, float totalW, boolean showVar, boolean showTrace) {
        allocate(totalW, showVar, true, showTrace);
        if (!showTrace) return;
        float body = Math.max(1f, totalW - SPLIT_W * ((showVar ? 1 : 0) + 1));
        float maxTrace = body - last.varW - MIN_CODE_W;
        float newTraceW = clampSection(last.traceW - dx, MIN_TRACE_W, maxTrace);
        traceRatio = newTraceW / body;
    }

    public void resetVar() {
        varRatio = -1f;
        float[] session = sessionRatios.get(posKey);
        if (session != null) session[0] = 0f;
        Core.settings.put(GLOBAL_VAR_KEY, -1f);
    }

    public void resetCode() {
        traceRatio = -1f;
        float[] session = sessionRatios.get(posKey);
        if (session != null) session[1] = 0f;
        Core.settings.put(GLOBAL_TRACE_KEY, -1f);
    }

    public void saveVarRatio() {
        if (varRatio > 0f) {
            float[] session = sessionRatios.get(posKey, () -> new float[2]);
            session[0] = varRatio;
            Core.settings.put(GLOBAL_VAR_KEY, varRatio);
        }
    }

    public void saveCodeRatio() {
        if (traceRatio > 0f) {
            float[] session = sessionRatios.get(posKey, () -> new float[2]);
            session[1] = traceRatio;
            Core.settings.put(GLOBAL_TRACE_KEY, traceRatio);
        }
    }
}
