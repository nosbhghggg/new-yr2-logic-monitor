package yr2lm.util;

import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.io.JsonIO;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.MemoryBlock;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 内存块读写工具。
 * <p>
 * v146 之后 MemoryBuild 不再暴露 {@code double[] memory}，改为私有的
 * {@code objectMemory} + {@code numberMemory} 双数组（内存格可以存放对象、字符串、null）。
 * 这里统一走 {@link mindustry.logic.LReadable#read(LVar, LVar)} /
 * {@link mindustry.logic.LWritable#write(LVar, LVar)} 公共接口访问，
 * 数值以 {@link Double} 表示，其余情况为原始对象（可能为 null）。
 */
public class MemUtil{
    private static final LVar posVar = new LVar("pos"), valVar = new LVar("val");

    /** 内存块容量。 */
    public static int capacity(MemoryBlock.MemoryBuild build){
        return ((MemoryBlock)build.block).memoryCapacity;
    }

    /** 读取一格。数值返回 {@link Double}，其余返回原始对象（可能为 null）。 */
    public static Object read(MemoryBlock.MemoryBuild build, int index){
        posVar.isobj = false;
        posVar.numval = index;
        valVar.isobj = false;
        valVar.numval = 0;
        valVar.objval = null;
        build.read(posVar, valVar);
        return valVar.isobj ? valVar.objval : (Object)valVar.numval;
    }

    /** 写入一格。数值用 {@link Number}，对象/null 直接传入。 */
    public static void write(MemoryBlock.MemoryBuild build, int index, Object value){
        if(index < 0 || index >= capacity(build)) return;
        posVar.isobj = false;
        posVar.numval = index;
        if(value instanceof Number number){
            //直接赋值，避免 LVar.setnum 把 NaN/Inf 转成 null
            valVar.isobj = false;
            valVar.numval = number.doubleValue();
            valVar.objval = null;
        }else{
            valVar.isobj = true;
            valVar.objval = value;
        }
        build.write(posVar, valVar);
    }

    /** 快照所有格子到本地数组。 */
    public static Object[] snapshot(MemoryBlock.MemoryBuild build){
        Object[] buf = new Object[capacity(build)];
        for(int i = 0; i < buf.length; i++) buf[i] = read(build, i);
        return buf;
    }

    /** 把快照写回内存块。 */
    public static void apply(MemoryBlock.MemoryBuild build, Object[] buf){
        if(buf == null) return;
        for(int i = 0; i < buf.length; i++) write(build, i, buf[i]);
    }

    private static String checkSpecialDouble(double value){
        if(Double.isNaN(value)) return "NaN";
        if(value == Double.POSITIVE_INFINITY) return "Inf";
        if(value == Double.NEGATIVE_INFINITY) return "-Inf";
        if(value == 0) return "0";
        return null;
    }

    /** 数值转文本。短表示原样显示; 过长或超大/超小的数截到 8~12 位有效数字, 避免精度噪声拖出一堆 0。 */
    public static String doubleToString(double value){
        String special = checkSpecialDouble(value);
        if(special != null) return special;
        double a = Math.abs(value);
        if(a >= 1e15 || a < 1e-6){
            //数量级极端, 用科学计数法, 8 位有效数字
            String s = new BigDecimal(value).round(new MathContext(8)).stripTrailingZeros().toString();
            return s.length() > 18 ? new BigDecimal(value).round(new MathContext(4)).stripTrailingZeros().toString() : s;
        }
        String s = BigDecimal.valueOf(value).round(new MathContext(12)).stripTrailingZeros().toPlainString();
        if(s.length() > 20) s = s.substring(0, 19) + "…";
        return s;
    }

    /**
     * 数值转文本, 按保留小数位数(0-10)显示, 四舍五入并去尾零;
     * 数量级极端时退回科学计数法。编辑输入仍应使用全精度的 {@link #doubleToString(double)}。
     */
    public static String doubleToString(double value, int decimals){
        String special = checkSpecialDouble(value);
        if(special != null) return special;
        double a = Math.abs(value);
        if(a >= 1e15 || a < 1e-6){
            int sig = Math.max(2, decimals + 1);
            String s = new BigDecimal(value).round(new MathContext(sig)).stripTrailingZeros().toString();
            return s.length() > 18 ? new BigDecimal(value).round(new MathContext(4)).stripTrailingZeros().toString() : s;
        }
        String s = BigDecimal.valueOf(value)
                .setScale(Math.max(0, Math.min(10, decimals)), RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        if(s.length() > 20) s = s.substring(0, 19) + "…";
        return s;
    }

    /** 任意格子的显示文本。 */
    public static String textOf(Object value){
        if(value == null) return "null";
        if(value instanceof Double d) return doubleToString(d);
        if(value instanceof String s) return "\"" + s + "\"";
        if(value instanceof Building b) return "@b:" + b.block.name + "#" + b.id;
        if(value instanceof Unit u) return "@u:" + u.type.name + "#" + u.id;
        if(value instanceof Team t) return "@t:" + t.name;
        return "@o:" + value;
    }

    /** 任意格子的显示文本, 数值按保留小数位数显示。 */
    public static String textOf(Object value, int decimals){
        if(value instanceof Double d) return doubleToString(d, decimals);
        return textOf(value);
    }

    /** 解析显示文本, 无法还原的对象返回 null。 */
    public static Object parseValue(String text){
        String s = text.trim();
        if(s.isEmpty()) return 0d;
        if(s.equals("null")) return null;
        switch(s){
            case "NaN" -> { return Double.NaN; }
            case "Inf" -> { return Double.POSITIVE_INFINITY; }
            case "-Inf" -> { return Double.NEGATIVE_INFINITY; }
        }
        if(s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        if(s.startsWith("@")) return null;
        try{
            return Double.parseDouble(s);
        }catch(NumberFormatException e){
            return 0d;
        }
    }

    /** 内存块导出为可复制文本, 每行 {@code 下标=值}。 */
    public static String toText(MemoryBlock.MemoryBuild build){
        StringBuilder sb = new StringBuilder("#yr2lm-memory\n");
        Object[] buf = snapshot(build);
        for(int i = 0; i < buf.length; i++){
            sb.append(i).append("=").append(textOf(buf[i])).append("\n");
        }
        return sb.toString();
    }

    /** 从文本写回内存块, 兼容旧版本导出的纯数字 JSON 数组。 */
    public static void fromText(MemoryBlock.MemoryBuild build, String text){
        if(text == null || text.isEmpty()) return;
        String trimmed = text.trim();
        if(trimmed.startsWith("[")){
            try{
                double[] legacy = JsonIO.read(double[].class, trimmed);
                if(legacy != null){
                    for(int i = 0; i < legacy.length; i++) write(build, i, legacy[i]);
                }
                return;
            }catch(RuntimeException ignored){}
        }
        for(String line : trimmed.split("\n")){
            String s = line.trim();
            if(s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf("=");
            if(eq < 0) continue;
            try{
                int index = Integer.parseInt(s.substring(0, eq).trim());
                write(build, index, parseValue(s.substring(eq + 1)));
            }catch(NumberFormatException ignored){}
        }
    }
}
