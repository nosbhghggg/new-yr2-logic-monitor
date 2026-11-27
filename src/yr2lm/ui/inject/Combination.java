package yr2lm.ui.inject;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;
import yr2lm.Yr2lmain;
import yr2lm.graphics.DrawExt;
import yr2lm.ui.window.Monitor;
import yr2lm.ui.window.MonitorFactory;
import yr2lm.ui.window.Yrailiuxa2;
import yr2lm.util.MemUtil;

import java.util.ArrayList;

/**
 * 主控窗口与监视器列表管理器。
 */
public class Combination extends Yrailiuxa2 {

    private Table combinationTable;
    private final Table listTable;
    private final ScrollPane listScroll;

    private boolean binding = false, copying = false, pasting = false;

    private class MonitorCell extends Table {
        public final Building building;
        private final Monitor monitor;

        public MonitorCell(Monitor monitorInit) {
            super();
            monitor = monitorInit;
            building = monitor.getBuilding();
            table(t -> {
                Table nameBox = t.table(tt -> tt.labelWrap(() -> {
                    if (Yr2Vars.isHovered(monitor) || Yr2Vars.isHovered(this)) {
                        return "[#" + Yr2Vars.themeHex + "]" + monitor.name;
                    } else return monitor.name;
                }).minWidth(0f).grow()).minWidth(0f).grow().pad(0, 10, 0, 5).get();
                nameBox.clicked(() -> {
                    monitor.hidden = false;
                    monitor.bringToFocus();
                });

                t.table(tt -> {
                    ImageButton visibleButton = tt.button(Icon.eyeSmall, Styles.emptyi, () -> {
                        monitor.hidden = !monitor.hidden;
                        if (!monitor.hidden) monitor.bringToFocus();
                    }).size(35).get();
                    // 直接修改 visibleButton.getStyle().imageUp，解决 ImageButton 构造器克隆 Style 导致图标不切换的 Bug
                    visibleButton.update(() -> visibleButton.getStyle().imageUp = monitor.hidden ? Icon.eyeOffSmall : Icon.eyeSmall);

                    tt.button(Icon.refresh, Styles.emptyi, monitor::init).size(35);
                    tt.button(Icon.trash, Styles.emptyi, () -> {
                        monitors.remove(monitor);
                        monitor.removeFromScene();
                        monitorsTableBuild();
                    }).size(35);
                }).pad(0, 5, 0, 10);
            }).height(35).minWidth(0f).growX();
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

        public void removeAndDispose() {
            monitor.removeFromScene();
            monitors.remove(monitor);
        }
    }

    private final ArrayList<Monitor> monitors = new ArrayList<>();
    private final ArrayList<MonitorCell> monitorCells = new ArrayList<>();
    private final Vec2 tmpMouseStage = new Vec2();

    private static String resolveVersionTitle() {
        if (Vars.mods != null) {
            Mods.LoadedMod mod = Vars.mods.getMod(Yr2lmain.class);
            if (mod != null && mod.meta != null && mod.meta.version != null) {
                return "yr2lm-" + mod.meta.version;
            }
        }
        return "yr2lm";
    }

    @SuppressWarnings("this-escape")
    public Combination() {
        super(resolveVersionTitle(), false);
        size.set(400, 300);
        minSize.set(200, 150);

        listTable = new Table();
        listTable.top();
        listScroll = new ScrollPane(listTable);
        Yrailiuxa2.configurePane(listScroll);
        listScroll.update(() -> {
            Yr2Vars.bindScrollFocus(listScroll);
            Element e = Yr2Vars.hoverElement();
            if (e != null && e.isDescendantOf(listScroll)) {
                for (MonitorCell cell : monitorCells) {
                    if (e == cell || e.isDescendantOf(cell)) {
                        cell.drawInfo();
                    }
                }
            }
            for (MonitorCell monitorCell : monitorCells) {
                if (Vars.world.build(monitorCell.building.pos()) != monitorCell.building) {
                    monitorCell.removeAndDispose();
                    monitorsTableBuild();
                    return;
                }
            }
        });

        combinationTableInit();
        mainTable.add(combinationTable).minWidth(0f).minHeight(0f).grow().left();
        hidden = true;
        onClose = this::closeMain;
    }

    private boolean isWorldTapValid() {
        return Core.input.keyTap(KeyCode.mouseLeft) && (Core.scene == null || !Core.scene.hasMouse());
    }

    private boolean isCancelTap() {
        return Core.input.keyTap(KeyCode.escape) || Core.input.keyTap(KeyCode.mouseRight);
    }

    private void combinationTableInit() {
        combinationTable = new Table(t -> {
            t.table(tt -> {
                tt.button("[grey]add", Styles.cleart, () -> {
                    binding = !binding;
                    if (binding) { copying = false; pasting = false; }
                }).minWidth(0f).grow().update(b -> {
                    b.setText(binding ? "add" : "[grey]add");
                    if (binding) {
                        if (isCancelTap()) { binding = false; return; }
                        Building selected = getWorldBuild();
                        if (MonitorFactory.isSupported(selected)) {
                            DrawExt.select(selected, Yr2Vars.themeColor);
                            if (isWorldTapValid()) {
                                addToCombination(selected);
                                selected.deselect();
                                binding = false;
                            }
                        } else if (isWorldTapValid()) {
                            binding = false;
                        }
                    }
                });

                tt.button("[grey]copy", Styles.cleart, () -> {
                    copying = !copying;
                    if (copying) { binding = false; pasting = false; }
                }).minWidth(0f).grow().update(b -> {
                    b.setText(copying ? "copy" : "[grey]copy");
                    if (copying) {
                        if (isCancelTap()) { copying = false; return; }
                        Building selected = getWorldBuild();
                        if (MonitorFactory.isSupported(selected)) {
                            DrawExt.select(selected, Color.valueOf("ffff00"));
                            if (isWorldTapValid()) {
                                copyConfig(selected);
                                selected.deselect();
                                copying = false;
                            }
                        } else if (isWorldTapValid()) {
                            copying = false;
                        }
                    }
                });

                tt.button("[grey]paste", Styles.cleart, () -> {
                    pasting = !pasting;
                    if (pasting) { binding = false; copying = false; }
                }).minWidth(0f).grow().update(b -> {
                    b.setText(pasting ? "paste" : "[grey]paste");
                    if (pasting) {
                        if (isCancelTap()) { pasting = false; return; }
                        Building selected = getWorldBuild();
                        if (MonitorFactory.isSupported(selected)) {
                            DrawExt.select(selected, Color.valueOf("ff00ff"));
                            if (isWorldTapValid()) {
                                pasteConfig(selected);
                                selected.deselect();
                            }
                        } else if (isWorldTapValid()) {
                            pasting = false;
                        }
                    }
                });
            }).height(50).minWidth(0f).growX();
            t.row();
            t.add(listScroll).minWidth(0f).minHeight(0f).grow();
        });
    }

    public void addToCombination(Building building) {
        openFloating(building, Yr2Vars.mouseStage(tmpMouseStage));
    }

    public Monitor getMonitor(Building building) {
        for (Monitor m : monitors) {
            if (m.getBuilding() == building) return m;
        }
        return null;
    }

    public Monitor openFloating(Building building, Vec2 stageMousePos) {
        Monitor monitor = getMonitor(building);
        if (monitor == null) {
            monitor = createMonitor(building, stageMousePos);
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
        placeMonitorAt(monitor, stageMousePos);
        monitorsTableBuild();
        return monitor;
    }

    private void placeMonitorAt(Monitor m, Vec2 stageMousePos) {
        m.hidden = false;
        m.pos.set(stageMousePos.x - m.size.x / 2f, stageMousePos.y - m.size.y / 2f);
        if (m.pinned) {
            m.syncWorldPos();
        } else {
            m.clampToScreen();
        }
        m.applyBoundsNow();
        m.bringToFocus();
    }

    private Monitor createMonitor(Building building, Vec2 stageMousePos) {
        return MonitorFactory.create(building, stageMousePos, false);
    }

    private void closeMain() {
        hidden = true;
    }

    private void monitorsTableBuild() {
        monitorCells.clear();
        listTable.clearChildren();
        listTable.top();
        for (Monitor monitor : monitors) {
            MonitorCell monitorCell = new MonitorCell(monitor);
            monitorCells.add(monitorCell);
            listTable.add(monitorCell).minWidth(0f).growX();
            listTable.row();
        }
    }

    private Building getWorldBuild() {
        int x = (int) (Core.input.mouseWorldX() / 8 + 0.5f);
        int y = (int) (Core.input.mouseWorldY() / 8 + 0.5f);
        return Vars.world.build(x, y);
    }

    private void copyConfig(Building building) {
        if (building instanceof LogicBlock.LogicBuild logicBuild) {
            Core.app.setClipboardText(logicBuild.code);
        } else if (building instanceof MemoryBlock.MemoryBuild memoryBuild) {
            Core.app.setClipboardText(MemUtil.toText(memoryBuild));
        } else if (building instanceof MessageBlock.MessageBuild messageBuild) {
            Core.app.setClipboardText(messageBuild.message.toString());
        }
    }

    private void pasteConfig(Building building) {
        String clipText = Core.app.getClipboardText();
        if (clipText == null) return;
        String normalized = clipText.replace("\r\n", "\n");

        if (building instanceof LogicBlock.LogicBuild logicBuild) {
            MemUtil.commitLogicCode(logicBuild, normalized);
        } else if (building instanceof MemoryBlock.MemoryBuild memoryBuild) {
            MemUtil.fromText(memoryBuild, normalized);
        } else if (building instanceof MessageBlock.MessageBuild messageBuild) {
            messageBuild.configure(normalized);
        }
    }

    public void clearMonitor() {
        for (Monitor monitor : monitors) {
            monitor.removeFromScene();
        }
        monitors.clear();
        monitorsTableBuild();
    }
}
