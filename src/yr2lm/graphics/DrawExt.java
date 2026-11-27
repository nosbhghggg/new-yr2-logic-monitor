package yr2lm.graphics;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Vec2;
import arc.util.Align;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import yr2lm.Yr2Vars;

/**
 * 世界与屏幕叠加层绘图工具。
 * <p>
 * 重构改进:
 * 1. 全面复用内部静态临时向量，彻底消除每帧在 select(Building) / info(...) 中 new Vec2() 造成的 GC 分配；
 * 2. 统一处理 Scene Stage 逻辑坐标到屏幕物理像素 (Camera.unproject) 的转换，确保高分屏 UI 缩放 != 100% 时矩形框与引线严丝合缝。
 */
public class DrawExt {
    private static final Vec2 tmpA = new Vec2();
    private static final Vec2 tmpB = new Vec2();
    private static final Vec2 tmpC = new Vec2();

    public static void select(float worldX, float worldY, float rad, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        Lines.stroke(zoom * 6f, Pal.gray);
        Lines.square(worldX, worldY, rad + zoom * 2f);
        Lines.stroke(zoom * 2f, color);
        Lines.square(worldX, worldY, rad);
        Draw.reset();
    }

    public static void select(Building building, Color color) {
        if (building == null || building.block == null) return;
        select(building.x, building.y, building.block.size * 4f, color);
    }

    public static void info(float x1, float y1, float x2, float y2, float rad, String name, Color color) {
        select(x2, y2, rad, color);
        worldLine(x1, y1, x2, y2, color);
        Fonts.outline.draw(name, x2, y2 - rad - 4f, color, 0.4f, false, Align.center);
    }

    public static void info(Vec2 pos, Unit unit, String name, Color color) {
        if (unit == null || unit.type == null) return;
        info(pos.x, pos.y, unit.x, unit.y, unit.type.hitSize, name, color);
    }

    public static void info(Vec2 pos, Building building, String name, Color color) {
        if (building == null || building.block == null) return;
        info(pos.x, pos.y, building.x, building.y, building.block.size * 4f, name, color);
    }

    /**
     * 在世界中绘制与 Scene Stage 逻辑矩形对应的边框 (自动换算 UI 缩放至物理像素再反投影)。
     */
    public static void screenRect(float stageX, float stageY, float stageW, float stageH, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        Yr2Vars.stageToScreenPhys(tmpA.set(stageX, stageY));
        Vec2 v1 = Core.camera.unproject(tmpA.x, tmpA.y);
        float blX = v1.x, blY = v1.y;

        Yr2Vars.stageToScreenPhys(tmpB.set(stageX + stageW, stageY + stageH));
        Vec2 v2 = Core.camera.unproject(tmpB.x, tmpB.y);
        float trX = v2.x, trY = v2.y;

        float w = trX - blX;
        float h = trY - blY;
        Lines.stroke(zoom * 6f, Pal.gray);
        Lines.rect(blX - zoom * 4f, blY - zoom * 4f, w + zoom * 8f, h + zoom * 8f);
        Lines.stroke(zoom * 2f, color);
        Lines.rect(blX - zoom * 2f, blY - zoom * 2f, w + zoom * 4f, h + zoom * 4f);
        Draw.reset();
    }

    /**
     * 从屏幕物理坐标 (sx1, sy1) 到 Scene Stage 逻辑坐标 (stageX2, stageY2) 绘制世界连线。
     */
    public static void screenLine(float physX1, float physY1, float stageX2, float stageY2, Color color) {
        Vec2 v1 = Core.camera.unproject(physX1, physY1);
        float p1x = v1.x, p1y = v1.y;
        Yr2Vars.stageToScreenPhys(tmpC.set(stageX2, stageY2));
        Vec2 v2 = Core.camera.unproject(tmpC.x, tmpC.y);
        worldLine(p1x, p1y, v2.x, v2.y, color);
    }

    public static void screenWorldLine(float physScreenX, float physScreenY, Unit unit, Color color) {
        if (unit == null) return;
        Vec2 v1 = Core.camera.unproject(physScreenX, physScreenY);
        worldLine(v1.x, v1.y, unit.x, unit.y, color);
    }

    public static void screenWorldLine(Vec2 physPos, Unit unit, Color color) {
        screenWorldLine(physPos.x, physPos.y, unit, color);
    }

    public static void screenWorldLine(float physScreenX, float physScreenY, Building building, Color color) {
        if (building == null) return;
        Vec2 v1 = Core.camera.unproject(physScreenX, physScreenY);
        worldLine(v1.x, v1.y, building.x, building.y, color);
    }

    public static void screenWorldLine(Vec2 physPos, Building building, Color color) {
        screenWorldLine(physPos.x, physPos.y, building, color);
    }

    public static void worldLine(float x1, float y1, float x2, float y2, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        Lines.stroke(zoom * 6f);
        Draw.color(Pal.gray, color.a);
        Lines.line(x1, y1, x2, y2);
        Lines.stroke(zoom * 2f, color);
        Lines.line(x1, y1, x2, y2);
        Draw.reset();
    }
}
