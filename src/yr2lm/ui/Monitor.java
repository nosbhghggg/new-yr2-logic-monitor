package yr2lm.ui;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;

/**
 * 监视窗口基类。
 * <p>
 * 架构特性:
 * 1. 自动感知世界方块生命周期: 仅在方块被摧毁(dead)时安全析构关闭，不再覆盖父类 update() 导致尺寸归零与误关闭；
 * 2. 鼠标悬浮面板时自动高亮世界中的关联方块。
 */
public class Monitor extends Yrailiuxa2 {
    protected final Table monitorTable;
    protected final Building building;

    public Monitor(String text, Building building, Vec2 pos) {
        super(text);
        this.building = building;
        this.monitorTable = new Table();
        this.pos.set(pos);
        size.set(400, 300);
        minSize.set(400, 300);
        mainTable.top().left();
        mainTable.add(monitorTable).top().left().grow();
        mainTable.update(() -> {
            Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
            if (e != null && e.isDescendantOf(mainTable))
                DrawExt.select(building, Yr2Vars.themeColor);
        });
    }

    @Override
    protected void onUpdate() {
        // 仅在游戏进行中且方块确实死亡被毁时安全释放
        if (building != null && Vars.state.isGame() && building.dead()) {
            requestClose();
        }
    }

    public void init() {}

    public Building getBuilding() {
        return building;
    }
}
