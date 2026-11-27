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
 * mlog 代码行语法高亮。
 * <p>
 * 纯代码优化清单:
 * 1. `loadFromGame()` 复用同一个 `StringBuilder` 实例，避免遍历上百个 `LogicIO.allStatements` 时每次 `new StringBuilder()`；
 * 2. `highlight()` 采用单遍流式 `appendColoredRange(out, hex, line, start, end)` 直接按字符流转义 `[` 为 `[[` 并写入输出缓冲，
 *    省去每个 token 先 `substring` 再 `.replace("[", "[[")` 再拼接 `"[#" + hex + "]..."` 产生的多份临时 String 对象。
 */
public class LogicHighlight {
    private static final String ENV = "1FAB89", VAR = "00C897", NUM = "D19A66",
            STR = "E5C07B", LABEL = "61AFEF", COMMENT = "7F848E";

    /** 指令名称 -> 加亮后的 HEX 颜色字符串。 */
    private static final ObjectMap<String, String> cats = new ObjectMap<>();
    private static boolean initialized = false;

    /** 刷新并重新扫描所有原生与模组积木指令及分类配色。 */
    public static synchronized void refresh() {
        cats.clear();
        loadFromGame();
        initialized = !cats.isEmpty();
    }

    private static void loadFromGame() {
        StringBuilder sb = new StringBuilder(64);
        try {
            if (LogicIO.allStatements != null) {
                for (Prov<LStatement> prov : LogicIO.allStatements) {
                    if (prov == null) continue;
                    try {
                        LStatement st = prov.get();
                        if (st == null) continue;
                        sb.setLength(0);
                        st.write(sb);
                        String text = sb.toString().trim();
                        if (!text.isEmpty()) {
                            int sp = text.indexOf(' ');
                            String op = (sp < 0 ? text : text.substring(0, sp));
                            if (!op.isEmpty() && !op.startsWith("#")) {
                                LCategory cat = st.category();
                                Color c = (cat != null && cat.color != null) ? cat.color : Pal.darkishGray;
                                cats.put(op, brightenHex(c));
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (LAssembler.customParsers != null) {
                String[] dummy1 = new String[1];
                String[] dummy5 = new String[]{"", "0", "0", "0", "0"};
                for (var entry : LAssembler.customParsers) {
                    String op = entry.key;
                    if (op == null || op.isEmpty()) continue;
                    try {
                        dummy1[0] = op;
                        LStatement st = entry.value.get(dummy1);
                        if (st != null && st.category() != null && st.category().color != null) {
                            cats.put(op, brightenHex(st.category().color));
                            continue;
                        }
                    } catch (Throwable ignored) {}
                    try {
                        dummy5[0] = op;
                        LStatement st = entry.value.get(dummy5);
                        if (st != null && st.category() != null && st.category().color != null) {
                            cats.put(op, brightenHex(st.category().color));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static Color brighten(Color out) {
        if (out == null) return Color.white;
        if (out.equals(Color.darkGray) || (Math.abs(out.r - out.g) < 0.02f && Math.abs(out.g - out.b) < 0.02f && out.r < 0.35f)) {
            return out.set(Color.lightGray);
        }
        float lum = 0.299f * out.r + 0.587f * out.g + 0.114f * out.b;
        float targetLum = 0.72f;
        if (lum < targetLum) {
            float t = Math.min(0.5f, (targetLum - lum) / (1.0f - lum + 0.001f) * 0.65f);
            out.lerp(Color.white, t);
        }
        return out;
    }

    public static String brightenHex(Color c) {
        if (c == null) return "FFFFFF";
        Color copy = new Color(c);
        brighten(copy);
        return copy.toString().substring(0, 6).toUpperCase();
    }

    /** 把一行 mlog 代码转为带 arc 标记的彩色文本 (单遍流式写入，减少中间字符串切片与 replace 分配)。 */
    public static String highlight(String line) {
        if (line == null || line.isEmpty()) return "";
        if (!initialized) refresh();
        int n = line.length();
        StringBuilder out = new StringBuilder(n + 48);
        int i = 0;
        boolean first = true;
        while (i < n) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                out.append(c);
                i++;
                continue;
            }
            if (c == ';') {
                out.append(';');
                first = true;
                i++;
                continue;
            }
            if (c == '#') {
                appendColoredRange(out, COMMENT, line, i, n);
                break;
            }
            int end;
            if (c == '"') {
                end = i + 1;
                while (end < n) {
                    char ch = line.charAt(end);
                    if (ch == '\\' && end + 1 < n) {
                        end += 2;
                        continue;
                    }
                    if (ch == '"') {
                        end++;
                        break;
                    }
                    end++;
                }
                appendColoredRange(out, STR, line, i, end);
            } else {
                end = i;
                while (end < n) {
                    char ch = line.charAt(end);
                    if (ch == ' ' || ch == '\t' || ch == '#' || ch == ';') break;
                    end++;
                }
                String token = line.substring(i, end);
                String hex = colorOf(token, first, token.charAt(token.length() - 1) == ':');
                appendColoredRange(out, hex, line, i, end);
            }
            first = false;
            i = end;
        }
        return out.toString();
    }

    private static void appendColoredRange(StringBuilder out, String hex, String src, int start, int end) {
        out.append("[#").append(hex).append(']');
        for (int k = start; k < end; k++) {
            char ch = src.charAt(k);
            if (ch == '[') out.append("[[");
            else out.append(ch);
        }
        out.append("[]");
    }

    private static String colorOf(String token, boolean first, boolean looksLabel) {
        if (first && looksLabel) return LABEL;
        if (first && cats.containsKey(token)) return cats.get(token);
        if (token.charAt(0) == '@') return ENV;
        if (isNumberOrLiteral(token)) return NUM;
        return VAR;
    }

    private static boolean isNumberOrLiteral(String token) {
        if (token.isEmpty()) return false;
        String t = token.toLowerCase();
        if (t.equals("true") || t.equals("false") || t.equals("null")) return true;
        if (t.startsWith("%[") && t.endsWith("]") && t.length() > 3) return true;
        if (t.charAt(0) == '%' && (t.length() == 7 || t.length() == 9)) return true;

        int start = (t.charAt(0) == '-' || t.charAt(0) == '+') ? 1 : 0;
        if (start >= t.length()) return false;

        if (t.startsWith("0x", start)) {
            if (start + 2 >= t.length()) return false;
            for (int i = start + 2; i < t.length(); i++) {
                if (Character.digit(t.charAt(i), 16) < 0) return false;
            }
            return true;
        }
        if (t.startsWith("0b", start)) {
            if (start + 2 >= t.length()) return false;
            for (int i = start + 2; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '0' && c != '1') return false;
            }
            return true;
        }

        boolean digit = false, dot = false, exp = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == '.' && !dot && !exp) {
                dot = true;
            } else if (c == 'e' && digit && !exp) {
                exp = true;
                if (i + 1 < t.length() && (t.charAt(i + 1) == '+' || t.charAt(i + 1) == '-')) i++;
            } else {
                return false;
            }
        }
        return digit;
    }
}
