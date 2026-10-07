package yr2lm.util;

import arc.func.Prov;
import arc.graphics.Color;
import arc.struct.ObjectMap;
import mindustry.gen.LogicIO;
import mindustry.graphics.Pal;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LStatement;

/**
 * mlog 代码行高亮。
 * <p>
 * 自动识别游戏原生与第三方模组注册的所有积木分类及指令，
 * 动态提取积木分类的原生主题色，经过感知加亮转换后作为语法高亮配色，
 * 完美自适应任意模组添加的新积木指令与新积木分类。
 */
public class LogicHighlight{
    private static final String ENV = "1FAB89", VAR = "00C897", NUM = "D19A66",
            STR = "E5C07B", LABEL = "61AFEF", COMMENT = "7F848E";

    /** 指令名称 -> 加亮后的 HEX 颜色字符串。 */
    private static final ObjectMap<String, String> cats = new ObjectMap<>();
    private static boolean initialized = false;

    static {
        refresh();
    }

    /** 刷新并重新扫描所有原生与模组积木指令及分类配色 (100% 动态读取注册表，彻底去除硬编码指令列表)。 */
    public static synchronized void refresh(){
        cats.clear();
        loadFromGame();
        initialized = true;
    }

    private static void loadFromGame(){
        try{
            if(LogicIO.allStatements != null){
                for(Prov<LStatement> prov : LogicIO.allStatements){
                    if(prov == null) continue;
                    try{
                        LStatement st = prov.get();
                        if(st == null) continue;
                        StringBuilder sb = new StringBuilder();
                        st.write(sb);
                        String text = sb.toString().trim();
                        if(!text.isEmpty()){
                            String op = (text.indexOf(' ') < 0 ? text : text.substring(0, text.indexOf(' ')));
                            if(!op.isEmpty() && !op.startsWith("#")){
                                LCategory cat = st.category();
                                Color c = (cat != null && cat.color != null) ? cat.color : Pal.darkishGray;
                                cats.put(op, brightenHex(c));
                            }
                        }
                    }catch(Throwable ignored){}
                }
            }
        }catch(Throwable ignored){}

        try{
            if(LAssembler.customParsers != null){
                for(var entry : LAssembler.customParsers){
                    String op = entry.key;
                    if(op == null || op.isEmpty()) continue;
                    try{
                        LStatement st = entry.value.get(new String[]{op});
                        if(st != null && st.category() != null && st.category().color != null){
                            cats.put(op, brightenHex(st.category().color));
                            continue;
                        }
                    }catch(Throwable ignored){}
                    try{
                        LStatement st = entry.value.get(new String[]{op, "0", "0", "0", "0"});
                        if(st != null && st.category() != null && st.category().color != null){
                            cats.put(op, brightenHex(st.category().color));
                        }
                    }catch(Throwable ignored){}
                }
            }
        }catch(Throwable ignored){}
    }

    /**
     * 对过暗的颜色进行感知提亮处理，保留原色调的同时大幅改善在深黑背景下的文本可读性。
     */
    public static Color brighten(Color out){
        if(out == null) return Color.white;
        if(out.equals(Color.darkGray) || (Math.abs(out.r - out.g) < 0.02f && Math.abs(out.g - out.b) < 0.02f && out.r < 0.35f)){
            return out.set(Color.lightGray);
        }
        float lum = 0.299f * out.r + 0.587f * out.g + 0.114f * out.b;
        float targetLum = 0.72f;
        if(lum < targetLum){
            float t = Math.min(0.5f, (targetLum - lum) / (1.0f - lum + 0.001f) * 0.65f);
            out.lerp(Color.white, t);
        }
        return out;
    }

    /** 将颜色加亮并转为 6 位 HEX 字符串。 */
    public static String brightenHex(Color c){
        if(c == null) return "FFFFFF";
        Color copy = new Color(c);
        brighten(copy);
        return copy.toString().substring(0, 6).toUpperCase();
    }

    /** 把一行 mlog 代码转为带 arc 标记的彩色文本。 */
    public static String highlight(String line){
        if(line == null || line.isEmpty()) return "";
        if(!initialized) refresh();
        StringBuilder out = new StringBuilder();
        int i = 0, n = line.length();
        boolean first = true;
        while(i < n){
            char c = line.charAt(i);
            if(c == ' ' || c == '\t'){
                out.append(c);
                i++;
                continue;
            }
            if(c == '#'){
                out.append(color(COMMENT, line.substring(i)));
                break;
            }
            int end;
            if(c == '"'){
                end = i + 1;
                while(end < n && line.charAt(end) != '"') end++;
                end = Math.min(end + 1, n);
            }else{
                end = i;
                while(end < n && line.charAt(end) != ' ' && line.charAt(end) != '\t') end++;
            }
            String token = line.substring(i, end);
            out.append(color(colorOf(token, first, token.endsWith(":")), token));
            first = false;
            i = end;
        }
        return out.toString();
    }

    private static String colorOf(String token, boolean first, boolean looksLabel){
        if(first && looksLabel) return LABEL;
        if(first && cats.containsKey(token)) return cats.get(token);
        if(token.startsWith("@")) return ENV;
        if(token.startsWith("\"")) return STR;
        if(isNumber(token)) return NUM;
        return VAR;
    }

    private static boolean isNumber(String token){
        String t = token.toLowerCase();
        if(t.equals("true") || t.equals("false") || t.equals("null")) return true;
        int start = (t.startsWith("-") || t.startsWith("+")) ? 1 : 0;
        if(start >= t.length()) return false;
        boolean digit = false, dot = false, exp = false;
        for(int i = start; i < t.length(); i++){
            char c = t.charAt(i);
            if(c >= '0' && c <= '9'){
                digit = true;
            }else if(c == '.' && !dot && !exp){
                dot = true;
            }else if((c == 'e') && digit && !exp){
                exp = true;
                if(i + 1 < t.length() && (t.charAt(i + 1) == '+' || t.charAt(i + 1) == '-')) i++;
            }else{
                return false;
            }
        }
        return digit;
    }

    /** arc 标记里 "[" 需转义为 "[["。 */
    private static String esc(String s){
        return s.replace("[", "[[");
    }

    private static String color(String hex, String text){
        return "[#" + hex + "]" + esc(text) + "[]";
    }
}
