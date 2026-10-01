package yr2lm.ui;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;

/**
 * 监视窗口基类。
 * <p>
 * 架构特性:
 * 1. 自动感知世界方块生命周期: 仅在方块被摧毁(dead)时安全析构关闭，不再覆盖父类 update() 导致尺寸归零与误关闭；
 * 2. 鼠标悬浮面板时自动高亮世界中的关联方块；
 * 3. 统一托管浮窗与内嵌面板模式的尺寸变化持久化，消除子类重复样板代码。
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
        if (building != null && Vars.state.isGame() && building.dead()) {
            requestClose();
        }
    }

    @Override
    protected void onResized() {
        MonitorFactory.saveSize(this);
    }

    public String getTypeName() {
        if (building instanceof LogicBlock.LogicBuild) return "Logic";
        if (building instanceof MemoryBlock.MemoryBuild) return "Mem";
        if (building instanceof MessageBlock.MessageBuild) return "Mes";
        return "Unknown";
    }

    public void init() {}

    public Building getBuilding() {
        return building;
    }
}
