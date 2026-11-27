package yr2lm.ui.window;

import arc.math.geom.Vec2;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;

/**
 * 绑定世界方块的监视窗口基类。
 * <p>
 * 重构改进:
 * 强制约束 `mainTable.add(monitorTable).minWidth(0f).minHeight(0f).grow()`，
 * 阻断子面板的 preferredWidth 向上冒泡撑破窗口边界，彻底解决快速缩小窗口时右下角拖拽手柄被挤出窗口外的问题。
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
        minSize.set(240, 180);
        mainTable.top().left();
        mainTable.add(monitorTable).top().left().minWidth(0f).minHeight(0f).grow();
        mainTable.update(() -> {
            if (Yr2Vars.isHovered(mainTable)) {
                DrawExt.select(building, Yr2Vars.themeColor);
            }
        });
    }

    @Override
    protected void onUpdate() {
        if (building != null && Vars.state.isGame()) {
            if (building.dead() || !building.isValid() || Vars.world.build(building.pos()) != building) {
                requestClose();
            }
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
