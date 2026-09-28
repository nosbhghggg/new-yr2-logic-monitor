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

public class DrawExt {
    public static void select(Vec2 pos, float rad, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        Lines.stroke(zoom * 6, Pal.gray);
        Lines.square(pos.x, pos.y, rad + zoom * 2);
        Lines.stroke(zoom * 2, color);
        Lines.square(pos.x, pos.y, rad);
        Draw.reset();
    }

    public static void select(Building building, Color color) {
        select(new Vec2(building.x, building.y), building.block.size * 4, color);
    }

    public static void info(Vec2 pos1, Vec2 pos2, float rad, String name, Color color) {
        select(pos2, rad, color);
        worldLine(pos1, pos2, color);
        Fonts.outline.draw(name, pos2.x, pos2.y - rad - 4, color, 0.4f, false, Align.center);
    }

    public static void info(Vec2 pos, Unit unit, String name, Color color) {
        info(pos, new Vec2(unit.x, unit.y), unit.type.hitSize, name, color);
    }

    public static void info(Vec2 pos, Building building, String name, Color color) {
        info(pos, new Vec2(building.x, building.y), building.block.size * 4, name, color);
    }

    public static void screenRect(float screenX, float screenY, float screenW, float screenH, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        // 注意: Arc 的 Core.camera.unproject(x, y) 内部返回全局复用的静态 tmpVector，
        // 连续调用会相互覆盖，必须在第二次调用前暂存 blX/blY 基础值，避免长宽归零塌陷。
        Vec2 v1 = Core.camera.unproject(screenX, screenY);
        float blX = v1.x, blY = v1.y;
        Vec2 v2 = Core.camera.unproject(screenX + screenW, screenY + screenH);
        float trX = v2.x, trY = v2.y;
        float w = trX - blX;
        float h = trY - blY;
        Lines.stroke(zoom * 6, Pal.gray);
        Lines.rect(blX - zoom * 4, blY - zoom * 4, w + zoom * 8, h + zoom * 8);
        Lines.stroke(zoom * 2, color);
        Lines.rect(blX - zoom * 2, blY - zoom * 2, w + zoom * 4, h + zoom * 4);
        Draw.reset();
    }

    public static void screenLine(float sx1, float sy1, float sx2, float sy2, Color color) {
        Vec2 v1 = Core.camera.unproject(sx1, sy1);
        float p1x = v1.x, p1y = v1.y;
        Vec2 v2 = Core.camera.unproject(sx2, sy2);
        worldLine(arc.util.Tmp.v1.set(p1x, p1y), arc.util.Tmp.v2.set(v2.x, v2.y), color);
    }

    public static void screenWorldLine(float screenX, float screenY, Unit unit, Color color) {
        worldLine(Core.camera.unproject(screenX, screenY), arc.util.Tmp.v1.set(unit.x, unit.y), color);
    }

    public static void screenWorldLine(Vec2 pos, Unit unit, Color color) {
        screenWorldLine(pos.x, pos.y, unit, color);
    }

    public static void screenWorldLine(float screenX, float screenY, Building building, Color color) {
        worldLine(Core.camera.unproject(screenX, screenY), arc.util.Tmp.v1.set(building.x, building.y), color);
    }

    public static void screenWorldLine(Vec2 pos, Building building, Color color) {
        screenWorldLine(pos.x, pos.y, building, color);
    }

    public static void worldLine(Vec2 pos1, Vec2 pos2, Color color) {
        float zoom = Core.camera.width / Core.graphics.getWidth();
        Lines.stroke(zoom * 6);
        Draw.color(Pal.gray, color.a);
        Lines.line(pos1.x, pos1.y, pos2.x, pos2.y);
        Lines.stroke(zoom * 2, color);
        Lines.line(pos1.x, pos1.y, pos2.x, pos2.y);
        Draw.reset();
    }
}
