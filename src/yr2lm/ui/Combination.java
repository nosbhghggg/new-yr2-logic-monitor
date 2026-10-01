package yr2lm.ui;

import arc.Core;
import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.io.JsonIO;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;
import yr2lm.Yr2lmain;
import yr2lm.graphics.DrawExt;
import yr2lm.util.MemUtil;

import java.util.ArrayList;

public class Combination extends Yrailiuxa2 {

    private Table combinationTable;
    private final Table monitorsTable;

    private boolean binding = false, copying = false, pasting = false;

    private class MonitorCell extends Table {
        public Building building;
        private final Monitor monitor;

        public MonitorCell(Monitor monitorInit) {
            super();
            monitor = monitorInit;
            building = monitor.getBuilding();
            table(t -> {
                t.table(tt -> tt.labelWrap(() -> {
                    Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
                    if (e != null && e.isDescendantOf(monitor)) {
                        return "[#" + Yr2Vars.themeHex + "]" + monitor.name;
                    } else return monitor.name;
                }).grow()).grow().pad(0, 10, 0, 5);
                t.table(tt -> {
                    ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
                    ImageButton visibleButton = tt.button(Icon.eyeSmall, Styles.emptyi, () -> {}).size(35).get();
                    visibleButton.clicked(() -> {
                        monitor.hidden = !monitor.hidden;
                        style.imageUp = monitor.hidden ? Icon.eyeOffSmall : Icon.eyeSmall;
                        visibleButton.setStyle(style);
                    });
                    tt.button(Icon.refresh, Styles.emptyi, monitor::init).size(35);
                    tt.button(Icon.trash, Styles.emptyi, () -> {
                        monitors.remove(monitor);
                        monitor.removeFromScene();
                        monitorsTableBuild();
                    }).size(35);
                }).pad(0, 5, 0, 10);
            }).height(35).growX();
        }

        public void drawInfo() {
            DrawExt.select(building, Yr2Vars.themeColor);
            DrawExt.screenWorldLine(Core.input.mouseX(), Core.input.mouseY(), building, Yr2Vars.themeColor);
            if (!monitor.hidden) {
                float sx = monitor.getActualScreenX();
                float sy = monitor.getActualScreenY();
                float sw = monitor.getActualScreenWidth();
                float sh = monitor.getActualScreenHeight();
                DrawExt.screenRect(sx, sy, sw, sh, Yr2Vars.themeColor);
                DrawExt.screenLine(Core.input.mouseX(), Core.input.mouseY(), sx + sw * 0.5f, sy + sh * 0.5f, Yr2Vars.themeColor);
            }
        }

        /** 从场景移除窗口并同步移出管理列表 (区别于基类仅移出场景的 removeFromScene)。 */
        public void removeAndDispose() {
            monitor.removeFromScene();
            monitors.remove(monitor);
        }
    }

    private final ArrayList<Monitor> monitors;
    private final ArrayList<Class<? extends Building>> molds;
    private final ArrayList<MonitorCell> monitorCells;

    @SuppressWarnings("this-escape")
    public Combination() {
        super("yr2lm-" + Vars.mods.getMod(Yr2lmain.class).meta.version, false);
        size.set(400, 300);
        minSize.set(200, 150);
        monitorsTable = new Table();
        combinationTableInit();
        mainTable.add(combinationTable).grow().left();
        monitors = new ArrayList<>();
        molds = new ArrayList<>();
        molds.add(LogicBlock.LogicBuild.class);
        molds.add(MemoryBlock.MemoryBuild.class);
        molds.add(MessageBlock.MessageBuild.class);
        monitorCells = new ArrayList<>();
        //默认不显示主窗口，由右下角建筑栏的 "y" 按钮唤出
        hidden = true;
        onClose = this::closeMain;
    }

    private void combinationTableInit() {
        combinationTable = new Table(t -> {
            t.table(tt -> {
                tt.button("[grey]add", Styles.cleart, () -> binding = !binding).grow().update(b -> {
                    if (binding) {
                        b.setText("add");
                        Building selected = getWorldBuild();
                        if (selected != null && molds.contains(selected.getClass())) {
                            DrawExt.select(selected, Yr2Vars.themeColor);
                            if (Core.input.isTouched()) {
                                addToCombination(selected);
                                selected.deselect();
                                b.setText("[grey]add");
                                binding = false;
                            }
                        }
                    }
                }).grow();
                tt.button("[grey]copy", Styles.cleart, () -> copying = !copying).grow().update(b -> {
                    if (copying) {
                        b.setText("copy");
                        Building selected = getWorldBuild();
                        if (selected != null && molds.contains(selected.getClass())) {
                            DrawExt.select(selected, Color.valueOf("ffff00"));
                            if (Core.input.isTouched()) {
                                copyConfig(selected);
                                selected.deselect();
                            }
                        }
                        if (Core.input.isTouched()) {
                            b.setText("[grey]copy");
                            copying = false;
                        }
                    }
                }).grow();
                tt.button("[grey]paste", Styles.cleart, () -> pasting = !pasting).grow().update(b -> {
                    if (pasting) {
                        b.setText("paste");
                        Building selected = getWorldBuild();
                        if (selected != null && molds.contains(selected.getClass())) {
                            DrawExt.select(selected, Color.valueOf("ff00ff"));
                            if (Core.input.isTouched()) {
                                pasteConfig(selected);
                                selected.deselect();
                            }
                        }
                        if (selected == null && Core.input.isTouched()) {
                            b.setText("[grey]paste");
                            pasting = false;
                        }

                    }
                }).grow();
            }).height(50).growX();
            t.row();
            t.add(monitorsTable).grow();
        });
    }

    public void addToCombination(Building building) {
        openFloating(building, Core.input.mouse());
    }

    public Monitor getMonitor(Building building) {
        for (Monitor m : monitors) {
            if (m.getBuilding() == building) return m;
        }
        return null;
    }

    public Monitor openFloating(Building building, Vec2 mousePos) {
        Monitor monitor = getMonitor(building);
        if (monitor == null) {
            monitor = createMonitor(building, mousePos);
            if (monitor == null) return null;
            final Monitor m = monitor;
            monitor.onClose = () -> {
                m.removeFromScene();
                monitors.remove(m);
                monitorsTableBuild();
            };
            monitor.addToScene();
            monitors.add(monitor);
        } else {
            monitor.hidden = false;
        }
        placeMonitorAt(monitor, mousePos);
        monitorsTableBuild();
        return monitor;
    }

    /** 将监视窗口定位于鼠标位置居中处, 按钉住状态同步世界坐标或钳制屏幕范围并置顶聚焦。 */
    private void placeMonitorAt(Monitor m, Vec2 mousePos) {
        m.hidden = false;
        m.pos.set(mousePos.x - m.size.x / 2f, mousePos.y - m.size.y / 2f);
        if (m.pinned) {
            m.syncWorldPos();
        } else {
            m.clampToScreen();
        }
        m.setPosition(m.pos.x, m.pos.y);
        m.setSize(m.size.x, m.size.y);
        m.bringToFocus();
    }

    private Monitor createMonitor(Building building, Vec2 mousePos) {
        return MonitorFactory.create(building, mousePos, false);
    }
    private void closeMain() {
        hidden = true;
    }

    private void monitorsTableBuild() {
        monitorCells.clear();
        monitorsTable.clear();
        monitorsTable.table(t -> t.pane(p -> {
            p.top();
            monitors.forEach(monitor -> {
                MonitorCell monitorCell = new MonitorCell(monitor);
                monitorCells.add(monitorCell);
                p.add(monitorCell).growX();
                p.row();
            });
        }).grow().update(p -> {
            Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
            if (e != null && e.isDescendantOf(p)) {
                p.requestScroll();
                monitorCells.stream().filter(e::isDescendantOf).forEach(MonitorCell::drawInfo);
            } else if (p.hasScroll()) Core.scene.setScrollFocus(null);
            for (MonitorCell monitorCell : monitorCells) {
                if (Vars.world.build(monitorCell.building.pos()) != monitorCell.building) {
                    monitorCell.removeAndDispose();
                    monitorsTableBuild();
                    return;
                }
            }
        }).with(Yrailiuxa2::configurePane)).grow();
    }

    private Building getWorldBuild() {
        int x = (int) (Core.input.mouseWorldX() / 8 + 0.5f);
        int y = (int) (Core.input.mouseWorldY() / 8 + 0.5f);
        return Vars.world.build(x, y);
    }

    private void copyConfig(Building building) {
        if (building instanceof LogicBlock.LogicBuild logicBuild)
            Core.app.setClipboardText(logicBuild.code);
        else if (building instanceof MemoryBlock.MemoryBuild memoryBuild)
            Core.app.setClipboardText(MemUtil.toText(memoryBuild));
        else if (building instanceof MessageBlock.MessageBuild messageBuild)
            Core.app.setClipboardText(JsonIO.write(messageBuild.message.toString()));
    }

    private void pasteConfig(Building building) {
        String clipText = Core.app.getClipboardText();
        if (clipText == null) return;

        if (building instanceof LogicBlock.LogicBuild logicBuild) {
            logicBuild.updateCode(clipText.replace("\r\n", "\n"));
        } else if (building instanceof MemoryBlock.MemoryBuild memoryBuild) {
            //新版内存格可存放对象, 用下标=值的文本格式导出, 同时兼容旧版纯数字 JSON
            MemUtil.fromText(memoryBuild, clipText.replace("\r\n", "\n"));
        } else if (building instanceof MessageBlock.MessageBuild messageBuild) {
            messageBuild.configure(clipText.replace("\r\n", "\n"));
        }
    }

    public void clearMonitor() {
        monitors.forEach(Yrailiuxa2::removeFromScene);
        monitors.clear();
        monitorsTableBuild();
    }

}

