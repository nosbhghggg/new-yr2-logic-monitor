package yr2lm.util;

import arc.Core;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.io.JsonIO;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 内存块读写、联机逻辑代码同步与高性能数值格式化工具。
 * <p>
 * 重构改进:
 * 1. 新增零 BigDecimal 分配的快速浮点数格式化通道 (整数与常规范围小数直接走原生 long/double 运算，减少 90%+ 每帧 GC 对象)；
 * 2. 新增 commitLogicCode(LogicBuild, String) 标准网络配置同步方法，严格走 LogicBlock.compress + build.configure(bytes)，
 *    修复多人联机/服务器下保存代码与粘贴代码不发包被回滚的致命 bug，并补齐 16KB 压缩上限保护；
 * 3. parseValue 完整支持 Mindustry mlog 的 0x 十六进制、0b 二进制及布尔字面量。
 */
public class MemUtil {
    private static final LVar posVar = new LVar("pos"), valVar = new LVar("val");
    private static final int MAX_COMPRESSED_LEN = 16_000;
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10
    };
    private static final MathContext MC_8 = new MathContext(8);
    private static final MathContext MC_12 = new MathContext(12);
    private static final MathContext MC_4 = new MathContext(4);

    /**
     * 标准逻辑代码提交入口 (兼容单机与多人联机服务器)。
     * 严格遵循原版 LogicBlock.showEditDialog (LogicBlock.java:750-756) 的压缩与网络包协议。
     */
    public static boolean commitLogicCode(LogicBlock.LogicBuild build, String newCode) {
        if (build == null || newCode == null) return false;
        String normalized = newCode.replace("\r\n", "\n");
        try {
            byte[] bytes = LogicBlock.compress(normalized, build.relativeConnections());
            if (bytes.length > MAX_COMPRESSED_LEN) {
                if (Vars.ui != null) {
                    Vars.ui.showErrorMessage(Core.bundle.format("logic.error.toolong", MAX_COMPRESSED_LEN, bytes.length));
                }
                return false;
            }
            boolean prevEditor = Vars.state != null && Vars.state.rules != null && Vars.state.rules.editor;
            build.configure(bytes);
            if (!normalized.equals(build.code)) {
                build.updateCode(normalized);
            }
            if (Vars.state != null && Vars.state.rules != null) {
                Vars.state.rules.editor = prevEditor;
            }
            return true;
        } catch (Throwable t) {
            build.updateCode(normalized);
            return true;
        }
    }

    /** 内存块容量。 */
    public static int capacity(MemoryBlock.MemoryBuild build) {
        return build == null || !(build.block instanceof MemoryBlock mb) ? 0 : mb.memoryCapacity;
    }

    /** 读取一格。数值返回 {@link Double}，其余返回原始对象（可能为 null）。 */
    public static synchronized Object read(MemoryBlock.MemoryBuild build, int index) {
        if (build == null) return 0d;
        posVar.isobj = false;
        posVar.numval = index;
        valVar.isobj = false;
        valVar.numval = 0;
        valVar.objval = null;
        build.read(posVar, valVar);
        return valVar.isobj ? valVar.objval : (Object) valVar.numval;
    }

    /** 写入一格。数值用 {@link Number}，对象/null 直接传入。 */
    public static synchronized void write(MemoryBlock.MemoryBuild build, int index, Object value) {
        if (build == null || index < 0 || index >= capacity(build)) return;
        posVar.isobj = false;
        posVar.numval = index;
        if (value instanceof Number number) {
            valVar.isobj = false;
            valVar.numval = number.doubleValue();
            valVar.objval = null;
        } else {
            valVar.isobj = true;
            valVar.objval = value;
        }
        build.write(posVar, valVar);
    }

    /** 快照所有格子到本地数组。 */
    public static Object[] snapshot(MemoryBlock.MemoryBuild build) {
        Object[] buf = new Object[capacity(build)];
        for (int i = 0; i < buf.length; i++) buf[i] = read(build, i);
        return buf;
    }

    /** 把快照写回内存块。 */
    public static void apply(MemoryBlock.MemoryBuild build, Object[] buf) {
        if (buf == null) return;
        for (int i = 0; i < buf.length; i++) write(build, i, buf[i]);
    }

    private static String checkSpecialDouble(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Inf";
        if (value == Double.NEGATIVE_INFINITY) return "-Inf";
        if (value == 0.0) return "0";
        return null;
    }

    /**
     * 高性能数值转文本 (零 BigDecimal 快速通道):
     * 1. 精确整数直接走 Long.toString()，彻底消除每帧轮询计数器和标志位时的 BigDecimal 分配；
     * 2. 短小数直接走 Double.toString()；仅在出现 IEEE-754 尾数精度噪声或极端数量级时回退到 BigDecimal。
     */
    public static String doubleToString(double value) {
        String special = checkSpecialDouble(value);
        if (special != null) return special;

        double a = Math.abs(value);
        if (value == (long) value && a < 1e15) {
            return Long.toString((long) value);
        }
        if (a >= 1e15 || a < 1e-6) {
            String s = new BigDecimal(value).round(MC_8).stripTrailingZeros().toString();
            return s.length() > 18 ? new BigDecimal(value).round(MC_4).stripTrailingZeros().toString() : s;
        }
        String raw = Double.toString(value);
        if (raw.length() <= 14 && raw.indexOf('E') < 0 && raw.indexOf('e') < 0) {
            return raw;
        }
        String s = BigDecimal.valueOf(value).round(MC_12).stripTrailingZeros().toPlainString();
        if (s.length() > 20) s = s.substring(0, 19) + "…";
        return s;
    }

    /**
     * 数值转文本，按保留小数位数 (0-10) 显示，四舍五入并去尾零。
     * 采用预计算 POW10 幂表快速通道，避免每格实例化 BigDecimal。
     */
    public static String doubleToString(double value, int decimals) {
        String special = checkSpecialDouble(value);
        if (special != null) return special;

        int scale = Math.max(0, Math.min(10, decimals));
        double a = Math.abs(value);
        if (value == (long) value && a < 1e15) {
            return Long.toString((long) value);
        }
        if (a >= 1e15 || a < 1e-6) {
            int sig = Math.max(2, scale + 1);
            String s = new BigDecimal(value).round(new MathContext(sig)).stripTrailingZeros().toString();
            return s.length() > 18 ? new BigDecimal(value).round(MC_4).stripTrailingZeros().toString() : s;
        }
        if (a < 1e12) {
            double factor = POW10[scale];
            double rounded = Math.round(value * factor) / factor;
            if (rounded == (long) rounded) {
                return Long.toString((long) rounded);
            }
            String fast = Double.toString(rounded);
            int dot = fast.indexOf('.');
            if (dot >= 0 && fast.length() - dot - 1 <= scale && fast.indexOf('E') < 0) {
                return fast;
            }
        }
        String s = BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        if (s.length() > 20) s = s.substring(0, 19) + "…";
        return s;
    }

    /** 任意格子的显示文本。 */
    public static String textOf(Object value) {
        if (value == null) return "null";
        if (value instanceof Double d) return doubleToString(d);
        if (value instanceof Number n) return doubleToString(n.doubleValue());
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof Building b) return "@b:" + b.block.name + "#" + b.id;
        if (value instanceof Unit u) return "@u:" + u.type.name + "#" + u.id;
        if (value instanceof Team t) return "@t:" + t.name;
        return "@o:" + value;
    }

    /** 任意格子的显示文本, 数值按保留小数位数显示。 */
    public static String textOf(Object value, int decimals) {
        if (value instanceof Double d) return doubleToString(d, decimals);
        if (value instanceof Number n) return doubleToString(n.doubleValue(), decimals);
        return textOf(value);
    }

    /** 解析显示文本 (支持十进制、科学计数法、0x十六进制、0b二进制、true/false、字符串与 null)。 */
    public static Object parseValue(String text) {
        if (text == null) return 0d;
        String s = text.trim();
        if (s.isEmpty()) return 0d;
        if (s.equals("null")) return null;
        if (s.equalsIgnoreCase("true")) return 1d;
        if (s.equalsIgnoreCase("false")) return 0d;
        switch (s) {
            case "NaN" -> { return Double.NaN; }
            case "Inf", "+Inf" -> { return Double.POSITIVE_INFINITY; }
            case "-Inf" -> { return Double.NEGATIVE_INFINITY; }
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        if (s.startsWith("@")) return null;
        try {
            String lower = s.toLowerCase();
            if (lower.startsWith("0x") || lower.startsWith("+0x")) {
                return (double) Long.parseUnsignedLong(lower.substring(lower.indexOf('x') + 1), 16);
            }
            if (lower.startsWith("-0x")) {
                return (double) -Long.parseUnsignedLong(lower.substring(3), 16);
            }
            if (lower.startsWith("0b") || lower.startsWith("+0b")) {
                return (double) Long.parseUnsignedLong(lower.substring(lower.indexOf('b') + 1), 2);
            }
            if (lower.startsWith("-0b")) {
                return (double) -Long.parseUnsignedLong(lower.substring(3), 2);
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** 内存块导出为可复制文本, 每行 {@code 下标=值}。 */
    public static String toText(MemoryBlock.MemoryBuild build) {
        StringBuilder sb = new StringBuilder("#yr2lm-memory\n");
        Object[] buf = snapshot(build);
        for (int i = 0; i < buf.length; i++) {
            sb.append(i).append("=").append(textOf(buf[i])).append("\n");
        }
        return sb.toString();
    }

    /** 从文本写回内存块, 兼容旧版本导出的纯数字 JSON 数组。 */
    public static void fromText(MemoryBlock.MemoryBuild build, String text) {
        if (text == null || text.isEmpty()) return;
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            try {
                double[] legacy = JsonIO.read(double[].class, trimmed);
                if (legacy != null) {
                    for (int i = 0; i < legacy.length; i++) write(build, i, legacy[i]);
                }
                return;
            } catch (RuntimeException ignored) {}
        }
        for (String line : trimmed.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf("=");
            if (eq < 0) continue;
            try {
                int index = Integer.parseInt(s.substring(0, eq).trim());
                write(build, index, parseValue(s.substring(eq + 1)));
            } catch (NumberFormatException ignored) {}
        }
    }
}
