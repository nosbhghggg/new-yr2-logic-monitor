package yr2lm;

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
}
