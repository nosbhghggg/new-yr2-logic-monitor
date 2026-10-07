package yr2lm;

import arc.Core;
import arc.graphics.Color;
import yr2lm.ui.Combination;

public class Yr2Vars {
    public static final Combination combination = new Combination();

    /**
     * 全局语义色彩系统:
     * 1. 主题强调色 (极光翠绿 #00e5a3): 聚焦当前执行指令行、当前选框、活跃图钉与主入口。
     * 2. 数据变动/差分色 (暖金琥珀 #ffb74d): 内存数值更新闪烁、变量快照变更 [diff]、流水数据突变。
     * 3. 流程跳转色 (破晓青蓝 #38bdf8): 逻辑代码与流水中的跳转指示 (➜ L)。
     * 4. 辅助元数据色 (冷灰标尺 #7e8c9a): 内存行号编号 (#0)、流水执行步号 (#12)、常量行号等。
     */
    public static final Color themeColor = Color.valueOf("00e5a3");
    public static final String themeHex = "00e5a3";

    public static final Color dataChangeColor = Color.valueOf("ffb74d");
    public static final String dataChangeHex = "ffb74d";

    public static final Color jumpColor = Color.valueOf("38bdf8");
    public static final String jumpHex = "38bdf8";

    public static final Color mutedColor = Color.valueOf("7e8c9a");
    public static final String mutedHex = "7e8c9a";

    // ---- Scene 逻辑坐标系工具 (物理像素 / UI 缩放): 所有窗口坐标与屏幕判定必须使用本组方法 ----
    /** Scene 逻辑宽 (随游戏内 UI 缩放变化)。 */
    public static float sceneW() {
        return Core.scene == null ? Core.graphics.getWidth() : Core.scene.getWidth();
    }

    /** Scene 逻辑高。 */
    public static float sceneH() {
        return Core.scene == null ? Core.graphics.getHeight() : Core.scene.getHeight();
    }

    /** 鼠标 X (换算至 Scene 逻辑坐标系; 物理坐标直接喂 scene.hit/窗口计算会在 UI 缩放 != 1 时错位)。 */
    public static float mouseX() {
        return ratio() * Core.input.mouseX();
    }

    /** 鼠标 Y (Scene 逻辑系)。 */
    public static float mouseY() {
        return ratio() * Core.input.mouseY();
    }

    private static float ratio() {
        float physW = Core.graphics.getWidth();
        return physW > 0 ? sceneW() / physW : 1f;
    }
}
