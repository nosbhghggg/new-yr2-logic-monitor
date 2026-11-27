package yr2lm;

import arc.Core;
import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import yr2lm.ui.inject.Combination;

/**
 * 全局状态、语义色彩系统、Scene 逻辑坐标系与 O(1) 悬停/滚轮焦点工具集。
 * <p>
 * 纯净无 i18n、无任何悬浮提示或 Toast 弹窗，保持 100% 原生图标交互风格。
 */
public class Yr2Vars {
    /** 延迟在 ClientLoadEvent 中初始化，杜绝类加载期强引用 Vars.mods / Styles 引发的 NPE 风险。 */
    public static Combination combination;

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

    private static final Vec2 stageTmp = new Vec2();

    public static synchronized Combination ensureCombination() {
        if (combination == null) {
            combination = new Combination();
        }
        return combination;
    }

    // ---- Scene 逻辑坐标系与 O(1) 悬停工具: 所有窗口坐标与屏幕判定必须使用本组方法 ----

    /** Scene 逻辑宽 (随游戏内 UI 缩放变化)。 */
    public static float sceneW() {
        return Core.scene == null ? Core.graphics.getWidth() : Core.scene.getWidth();
    }

    /** Scene 逻辑高。 */
    public static float sceneH() {
        return Core.scene == null ? Core.graphics.getHeight() : Core.scene.getHeight();
    }

    /** 鼠标 X (换算至 Scene Stage 逻辑坐标系; 兼容任意 UI 缩放比例与窗口 Margin)。 */
    public static float mouseX() {
        if (Core.scene == null) return Core.input.mouseX();
        return Core.scene.screenToStageCoordinates(stageTmp.set(Core.input.mouseX(), Core.input.mouseY())).x;
    }

    /** 鼠标 Y (Scene Stage 逻辑坐标系)。 */
    public static float mouseY() {
        if (Core.scene == null) return Core.input.mouseY();
        return Core.scene.screenToStageCoordinates(stageTmp.set(Core.input.mouseX(), Core.input.mouseY())).y;
    }

    /** 获取当前鼠标在 Scene Stage 逻辑坐标系下的向量副本。 */
    public static Vec2 mouseStage(Vec2 out) {
        out.set(Core.input.mouseX(), Core.input.mouseY());
        if (Core.scene != null) {
            Core.scene.screenToStageCoordinates(out);
        }
        return out;
    }

    /**
     * 世界坐标 -> Scene Stage 逻辑坐标。
     * 修复窗口钉在世界上 (Pinned) 时在 UI 缩放 != 100% 下的位置漂移。
     */
    public static void worldToStage(float worldX, float worldY, Vec2 out) {
        out.set(worldX, worldY);
        if (Core.camera != null) {
            Core.camera.project(out);
        }
        screenPhysToStage(out);
    }

    /**
     * 屏幕物理像素坐标 (左下角原点, 如 Core.input.mouseX/Y 或 Camera.project) -> Scene Stage 逻辑坐标。
     */
    public static void screenPhysToStage(Vec2 inOut) {
        float physW = Core.graphics.getWidth();
        float physH = Core.graphics.getHeight();
        if (Core.scene != null && physW > 0 && physH > 0) {
            inOut.x = (inOut.x / physW) * Core.scene.getWidth() - Core.scene.marginLeft;
            inOut.y = (inOut.y / physH) * Core.scene.getHeight() - Core.scene.marginBottom;
        }
    }

    /**
     * Scene Stage 逻辑坐标 -> 屏幕物理像素坐标 (左下角原点, 供 Camera.unproject 使用)。
     */
    public static void stageToScreenPhys(Vec2 inOut) {
        float physW = Core.graphics.getWidth();
        float physH = Core.graphics.getHeight();
        if (Core.scene != null && Core.scene.getWidth() > 0 && Core.scene.getHeight() > 0) {
            inOut.x = ((inOut.x + Core.scene.marginLeft) / Core.scene.getWidth()) * physW;
            inOut.y = ((inOut.y + Core.scene.marginBottom) / Core.scene.getHeight()) * physH;
        }
    }

    /**
     * O(1) 获取当前帧鼠标悬停元素。
     * 直接复用 Arc Scene.act() 已计算好的 mouseOverElement 缓存，零场景树 hit() 开销。
     */
    public static Element hoverElement() {
        if (Core.scene == null) return null;
        Element cached = Core.scene.getHoverElement();
        return cached != null ? cached : Core.scene.hit(mouseX(), mouseY(), true);
    }

    /** O(1) 判断目标元素或其子元素当前是否被鼠标悬停。 */
    public static boolean isHovered(Element container) {
        if (container == null) return false;
        Element h = hoverElement();
        return h != null && (h == container || h.isDescendantOf(container));
    }

    /**
     * 精准绑定与释放滚动焦点:
     * 1. 当鼠标移入本 ScrollPane 时，申请滚轮焦点 (pane.requestScroll())；
     * 2. 当鼠标移出本 ScrollPane (例如移到外部世界地图上) 且当前全局滚轮焦点仍被本 ScrollPane 占用时，
     *    立即调用 Core.scene.setScrollFocus(null) 归还滚轮控制权，确保玩家在面板外滚动鼠标能立即丝滑缩放地图！
     */
    public static void bindScrollFocus(ScrollPane pane) {
        if (pane == null || Core.scene == null) return;
        if (isHovered(pane)) {
            pane.requestScroll();
        } else if (Core.scene.getScrollFocus() == pane) {
            Core.scene.setScrollFocus(null);
        }
    }
}
