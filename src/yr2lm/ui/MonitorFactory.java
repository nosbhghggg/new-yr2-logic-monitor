package yr2lm.ui;

import arc.Core;
import arc.math.geom.Vec2;
import mindustry.gen.Building;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;

import java.math.BigDecimal;

/**
 * 监视器统一构建工厂与偏好尺寸管理者。
 * 集中管理各方块监视窗口的创建逻辑与浮窗/内嵌面板的尺寸持久化。
 */
public class MonitorFactory {
    public static float lastLogicW = -1f, lastLogicH = -1f;
    public static float lastMemW = -1f, lastMemH = -1f;
    public static float lastMesW = -1f, lastMesH = -1f;

    public static float paneLogicW = -1f, paneLogicH = -1f;
    public static float paneMemW = -1f, paneMemH = -1f;
    public static float paneMesW = -1f, paneMesH = -1f;

    public static void loadSizes() {
        lastLogicW = Core.settings.getFloat("yr2lm.win.lastLogicW", -1f);
        lastLogicH = Core.settings.getFloat("yr2lm.win.lastLogicH", -1f);
        lastMemW = Core.settings.getFloat("yr2lm.win.lastMemW", -1f);
        lastMemH = Core.settings.getFloat("yr2lm.win.lastMemH", -1f);
        lastMesW = Core.settings.getFloat("yr2lm.win.lastMesW", -1f);
        lastMesH = Core.settings.getFloat("yr2lm.win.lastMesH", -1f);

        paneLogicW = Core.settings.getFloat("yr2lm.win.paneLogicW", -1f);
        paneLogicH = Core.settings.getFloat("yr2lm.win.paneLogicH", -1f);
        paneMemW = Core.settings.getFloat("yr2lm.win.paneMemW", -1f);
        paneMemH = Core.settings.getFloat("yr2lm.win.paneMemH", -1f);
        paneMesW = Core.settings.getFloat("yr2lm.win.paneMesW", -1f);
        paneMesH = Core.settings.getFloat("yr2lm.win.paneMesH", -1f);
    }

    public static void persistSize(String key, float w, float h) {
        Core.settings.put("yr2lm.win." + key + "W", w);
        Core.settings.put("yr2lm.win." + key + "H", h);
    }

    public static void saveSize(Monitor monitor) {
        saveSize(monitor.getTypeName(), monitor.attachedPaneMode, monitor.size.x, monitor.size.y);
    }

    public static void saveSize(String type, boolean pane, float w, float h) {
        if ("Logic".equals(type)) {
            if (pane) { paneLogicW = w; paneLogicH = h; persistSize("paneLogic", w, h); }
            else { lastLogicW = w; lastLogicH = h; persistSize("lastLogic", w, h); }
        } else if ("Mem".equals(type)) {
            if (pane) { paneMemW = w; paneMemH = h; persistSize("paneMem", w, h); }
            else { lastMemW = w; lastMemH = h; persistSize("lastMem", w, h); }
        } else if ("Mes".equals(type)) {
            if (pane) { paneMesW = w; paneMesH = h; persistSize("paneMes", w, h); }
            else { lastMesW = w; lastMesH = h; persistSize("lastMes", w, h); }
        }
    }

    public static float[] prefSize(Building selected) {
        return sizeFor(selected, false);
    }

    public static float[] paneSize(Building selected) {
        return sizeFor(selected, true);
    }

    private static float[] sizeFor(Building selected, boolean pane) {
        float screenW = (Core.graphics != null) ? Core.graphics.getWidth() : 1920f;
        float screenH = (Core.graphics != null) ? Core.graphics.getHeight() : 1080f;
        float w, h;
        if (selected instanceof LogicBlock.LogicBuild) {
            float defaultLogicW = screenW >= 860f ? 800f : 540f;
            w = pane ? (paneLogicW > 0 ? paneLogicW : defaultLogicW) : (lastLogicW > 0 ? lastLogicW : defaultLogicW);
            h = pane ? (paneLogicH > 0 ? paneLogicH : 520f) : (lastLogicH > 0 ? lastLogicH : 520f);
        } else if (selected instanceof MemoryBlock.MemoryBuild) {
            w = pane ? (paneMemW > 0 ? paneMemW : 930f) : (lastMemW > 0 ? lastMemW : 930f);
            h = pane ? (paneMemH > 0 ? paneMemH : 460f) : (lastMemH > 0 ? lastMemH : 460f);
        } else {
            w = pane ? (paneMesW > 0 ? paneMesW : 460f) : (lastMesW > 0 ? lastMesW : 460f);
            h = pane ? (paneMesH > 0 ? paneMesH : 300f) : (lastMesH > 0 ? lastMesH : 300f);
        }
        return new float[]{Math.min(w, screenW - 20f), Math.min(h, screenH - 40f)};
    }

    public static Monitor create(Building building, Vec2 pos, boolean attached) {
        if (building == null) return null;
        String x = BigDecimal.valueOf(building.x / 8).stripTrailingZeros().toPlainString();
        String y = BigDecimal.valueOf(building.y / 8).stripTrailingZeros().toPlainString();
        String blockName = (building.block != null ? building.block.name : "processor");
        String title = blockName + "(" + x + ", " + y + ")";

        Monitor monitor;
        if (building instanceof LogicBlock.LogicBuild lb) {
            monitor = new LogicMonitor(title, lb, pos);
        } else if (building instanceof MemoryBlock.MemoryBuild mb) {
            monitor = new MemoryMonitor(title, mb, pos);
        } else if (building instanceof MessageBlock.MessageBuild mb) {
            monitor = new MessageMonitor(title, mb, pos);
        } else {
            return null;
        }

        monitor.attachedPaneMode = attached;
        float[] sz = attached ? paneSize(building) : prefSize(building);
        monitor.size.set(sz[0], sz[1]);
        return monitor;
    }
}
