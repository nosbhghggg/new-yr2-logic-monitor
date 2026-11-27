package yr2lm.ui.inject;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.fragments.BlockConfigFragment;
import mindustry.world.blocks.logic.MemoryBlock;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Monitor;
import yr2lm.ui.window.MonitorFactory;

import java.lang.reflect.Field;

/**
 * 原生方块配置栏防冲突注入器与右下角快捷入口。
 */
public class ConfigInjector {
    private static Field tableField;
    private static boolean inited = false;

    private static Building attachedBuilding = null;
    private static Monitor attachedMonitor = null;

    private static Building quickMemoryBuilding = null;
    private static Table quickMemoryBar = null;

    private static DragPreview dragPreview = null;

    private static Table cachedConfigTable = null;
    private static Table cachedInputTable = null;
    private static Button cachedYBtn = null;
    private static Button cachedRefBtn = null;
    private static final Vec2 tmpStageVec = new Vec2();

    private static final arc.struct.ObjectSet<String> warned = new arc.struct.ObjectSet<>();

    private static void warnOnce(String where, Throwable t) {
        if (warned.add(where)) arc.util.Log.errTag("yr2lm", where + " 失败: " + t);
    }

    static {
        try {
            tableField = BlockConfigFragment.class.getDeclaredField("table");
            tableField.setAccessible(true);
        } catch (Throwable t) {
            warnOnce("取游戏配置表字段", t);
        }
    }

    public static void init() {
        if (inited) return;
        inited = true;
        MonitorFactory.loadSizes();
        Events.run(EventType.Trigger.update, ConfigInjector::update);

        Events.on(EventType.TapEvent.class, e -> {
            if (!Vars.state.isGame() || Vars.player == null || e.player != Vars.player || e.tile == null) return;
            Building b = e.tile.build;
            if (b instanceof MemoryBlock.MemoryBuild mem && mem.interactable(Vars.player.team())) {
                if (quickMemoryBuilding == mem) {
                    closeQuickMemoryBar();
                    closeAttached();
                } else {
                    showQuickMemoryBar(mem);
                }
            } else if (quickMemoryBuilding != null) {
                closeQuickMemoryBar();
            }
        });
    }

    private static void showQuickMemoryBar(MemoryBlock.MemoryBuild mem) {
        closeQuickMemoryBar();
        closeAttached();
        quickMemoryBuilding = mem;

        Table bar = new Table();
        bar.name = "yr2lm-memory-quickbar";
        injectButton(bar, mem);
        bar.pack();
        bar.update(() -> {
            if (!Vars.state.isGame() || mem.dead() || !mem.isValid() || Vars.world.build(mem.pos()) != mem) {
                closeQuickMemoryBar();
                closeAttached();
                return;
            }
            float worldTopY = mem.y + mem.block.size * Vars.tilesize / 2f + 1f;
            Yr2Vars.worldToStage(mem.x, worldTopY, tmpStageVec);
            bar.setPosition(tmpStageVec.x, tmpStageVec.y, Align.bottom);
        });
        if (Core.scene != null) {
            Core.scene.add(bar);
            quickMemoryBar = bar;
        }
    }

    private static void closeQuickMemoryBar() {
        if (quickMemoryBar != null) {
            quickMemoryBar.remove();
            quickMemoryBar = null;
        }
        quickMemoryBuilding = null;
    }

    public static void update() {
        if (!Vars.state.isGame()) {
            closeAttached();
            closeQuickMemoryBar();
            if (cachedYBtn != null) {
                cachedYBtn.remove();
                cachedYBtn = null;
            }
            cachedInputTable = null;
            cachedRefBtn = null;
            cachedConfigTable = null;
            return;
        }

        checkPlacementUI();

        var config = Vars.control == null || Vars.control.input == null ? null : Vars.control.input.config;
        if (config != null && config.isShown() && quickMemoryBuilding != null && config.getSelected() != quickMemoryBuilding) {
            closeQuickMemoryBar();
        }

        if (attachedBuilding != null) {
            boolean configAlive = config != null && config.isShown() && config.getSelected() == attachedBuilding;
            boolean memAlive = quickMemoryBuilding == attachedBuilding && quickMemoryBar != null && quickMemoryBar.hasParent();
            if (!configAlive && !memAlive) {
                closeAttached();
            }
        }

        if (config == null || !config.isShown()) return;
        Building selected = config.getSelected();
        if (!MonitorFactory.isSupported(selected)) return;

        Table configTable = getConfigTable();
        if (configTable == null) return;

        if (configTable.find("yr2lm-config-btn") == null) {
            injectButton(configTable, selected);
        }
    }

    private static Table getConfigTable() {
        try {
            var config = Vars.control.input.config;
            if (cachedConfigTable != null && cachedConfigTable.hasParent()) {
                return cachedConfigTable.visible ? cachedConfigTable : null;
            }
            if (tableField != null) {
                cachedConfigTable = (Table) tableField.get(config);
                return cachedConfigTable != null && cachedConfigTable.visible ? cachedConfigTable : null;
            }
        } catch (Throwable t) {
            warnOnce("读游戏配置表", t);
        }
        return null;
    }

    private static boolean isPureButtonRow(Table subTable) {
        var children = subTable.getChildren();
        if (children.isEmpty()) return false;
        boolean foundBtn = false;
        for (Element e : children) {
            if (e instanceof Button) {
                foundBtn = true;
            } else if (e instanceof Table) {
                return false;
            }
        }
        return foundBtn;
    }

    @SuppressWarnings("rawtypes")
    private static void attachGearButtonSafely(Table configTable, ImageButton injectBtn) {
        Seq<Cell> cells = configTable.getCells();

        for (int i = 0; i < cells.size; i++) {
            Element actor = cells.get(i).get();
            if (actor instanceof Table subTable && isPureButtonRow(subTable)) {
                subTable.add(injectBtn).size(40f);
                subTable.pack();
                configTable.pack();
                return;
            }
            if (cells.get(i).isEndRow()) break;
        }

        int firstRowEndIndex = -1;
        boolean firstRowHasButtons = false;
        for (int i = 0; i < cells.size; i++) {
            Element actor = cells.get(i).get();
            if (actor instanceof Button) firstRowHasButtons = true;
            if (cells.get(i).isEndRow()) {
                firstRowEndIndex = i;
                break;
            }
        }

        if (firstRowHasButtons && firstRowEndIndex >= 0) {
            consolidateFirstRowButtons(configTable, firstRowEndIndex, injectBtn);
            return;
        }

        configTable.add(injectBtn).size(40f);
        configTable.pack();
    }

    private static void consolidateFirstRowButtons(Table configTable, int firstRowEndIndex, ImageButton injectBtn) {
        Seq<?> cells = configTable.getCells();
        Table rowBar = new Table();
        rowBar.name = "yr2lm-btn-row";

        for (int i = 0; i <= firstRowEndIndex; i++) {
            Cell<?> cell = (Cell<?>) cells.get(i);
            Element actor = cell.get();
            if (actor != null) {
                float w = cell.maxWidth() > 0 ? cell.maxWidth() : 40f;
                float h = cell.maxHeight() > 0 ? cell.maxHeight() : 40f;
                actor.remove();
                rowBar.add(actor).size(w, h);
            }
        }
        rowBar.add(injectBtn).size(40f);
        rowBar.pack();

        Seq<Element> tailActors = new Seq<>();
        Seq<Boolean> tailEndRows = new Seq<>();
        for (int i = firstRowEndIndex + 1; i < cells.size; i++) {
            Cell<?> cell = (Cell<?>) cells.get(i);
            tailActors.add(cell.get());
            tailEndRows.add(cell.isEndRow());
        }

        configTable.clearChildren();
        configTable.add(rowBar).center();
        if (!tailActors.isEmpty()) {
            configTable.row();
            for (int i = 0; i < tailActors.size; i++) {
                Element tail = tailActors.get(i);
                if (tail != null) {
                    configTable.add(tail);
                    if (tailEndRows.get(i)) configTable.row();
                }
            }
        }
        configTable.pack();
    }

    private static void injectButton(Table configTable, Building selected) {
        if (configTable.find("yr2lm-config-btn") != null) return;

        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.cleari);
        style.imageUp = Icon.settings;

        ImageButton injectBtn = new ImageButton(style);
        injectBtn.name = "yr2lm-config-btn";

        injectBtn.addListener(new InputListener() {
            float startStageX, startStageY;
            boolean dragging = false;
            static final float DRAG_THRESHOLD = 14f;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startStageX = Yr2Vars.mouseX();
                startStageY = Yr2Vars.mouseY();
                dragging = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!dragging) {
                    float dx = Yr2Vars.mouseX() - startStageX;
                    float dy = Yr2Vars.mouseY() - startStageY;
                    if (dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        dragging = true;
                        Monitor existing = Yr2Vars.ensureCombination().getMonitor(selected);
                        float[] sz = (existing != null) ? new float[]{existing.size.x, existing.size.y} : MonitorFactory.prefSize(selected);
                        dragPreview = new DragPreview(sz[0], sz[1], selected.block.name);
                        if (Core.scene != null) Core.scene.root.addChild(dragPreview);
                    }
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (dragPreview != null) {
                    dragPreview.remove();
                    dragPreview = null;
                }

                if (dragging) {
                    closeAttached();
                    closeQuickMemoryBar();
                    Monitor m = Yr2Vars.ensureCombination().openFloating(selected, Yr2Vars.mouseStage(tmpStageVec));
                    if (m != null) {
                        m.setTransform(true);
                        m.setOrigin(Align.center);
                        m.setScale(0.85f);
                        m.color.a = 0f;
                        m.actions(
                            Actions.parallel(
                                Actions.fadeIn(0.16f, Interp.pow2Out),
                                Actions.scaleTo(1f, 1f, 0.16f, Interp.pow3Out)
                            ),
                            Actions.run(() -> {
                                if (!m.pinned) m.setTransform(false);
                            })
                        );
                    }
                    if (Vars.control != null && Vars.control.input != null && Vars.control.input.config != null) {
                        Vars.control.input.config.hideConfig();
                    }
                } else {
                    toggleAttached(configTable, selected);
                }
                dragging = false;
            }
        });

        attachGearButtonSafely(configTable, injectBtn);
    }

    private static void toggleAttached(Table configTable, Building selected) {
        if (attachedBuilding == selected && attachedMonitor != null && attachedMonitor.hasParent()) {
            closeAttached();
            return;
        }

        closeAttached();
        attachedBuilding = selected;

        attachedMonitor = MonitorFactory.create(selected, Yr2Vars.mouseStage(tmpStageVec), true);
        if (attachedMonitor == null) return;
        attachedMonitor.name = "yr2lm-attached-pane";

        attachedMonitor.setTransform(true);
        attachedMonitor.setOrigin(Align.top);
        attachedMonitor.setScale(1f, 0f);
        attachedMonitor.actions(
            Actions.scaleTo(1f, 1f, 0.12f, Interp.pow3Out),
            Actions.run(() -> attachedMonitor.setTransform(false))
        );

        attachedMonitor.update(() -> {
            if (configTable != null && configTable.parent != null) {
                float anchorX = configTable.x + configTable.getWidth() / 2f;
                float anchorY = configTable.y - 4f;
                attachedMonitor.setSize(attachedMonitor.size.x, attachedMonitor.size.y);
                attachedMonitor.setPosition(anchorX, anchorY, Align.top);
            }
        });

        if (configTable.parent != null) {
            configTable.parent.addChild(attachedMonitor);
        } else if (Core.scene != null) {
            Core.scene.add(attachedMonitor);
        }
    }

    private static Button findFirstButton(Element root) {
        if (root.name != null && root.name.equals("yr2lm-reopen-button")) return null;
        if (root instanceof Button b) return b;
        if (root instanceof Table t) {
            for (Element c : t.getChildren()) {
                Button r = findFirstButton(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void checkPlacementUI() {
        if (Core.scene == null) return;

        if (cachedInputTable == null || !cachedInputTable.hasParent() || cachedInputTable.getScene() == null) {
            Element found = Core.scene.find("inputTable");
            cachedInputTable = (found instanceof Table t) ? t : null;
            cachedRefBtn = null;
        }

        if (cachedInputTable == null) {
            if (cachedYBtn != null) {
                cachedYBtn.remove();
                cachedYBtn = null;
            }
            return;
        }

        if (cachedYBtn == null || !cachedYBtn.hasParent() || cachedYBtn.getScene() == null) {
            Button yBtn = new Button(Styles.clearNonei);
            yBtn.name = "yr2lm-reopen-button";
            Label yLbl = new Label(() -> "[#" + Yr2Vars.themeHex + "]y[]");
            yLbl.setStyle(Styles.outlineLabel);
            yLbl.setAlignment(Align.center);
            yBtn.add(yLbl).center().grow();
            yBtn.setSize(48f, 48f);
            yBtn.tapped(() -> {
                Combination comb = Yr2Vars.ensureCombination();
                comb.hidden = !comb.hidden;
                if (!comb.hidden) {
                    comb.bringToFocus();
                }
            });
            yBtn.visible(() -> Vars.state.isGame()
                    && Yr2Vars.combination != null
                    && Yr2Vars.combination.hidden
                    && cachedInputTable != null
                    && cachedInputTable.visible
                    && cachedInputTable.hasParent());
            Core.scene.add(yBtn);
            cachedYBtn = yBtn;
        }

        if (cachedRefBtn == null || !cachedRefBtn.hasParent() || !cachedRefBtn.isDescendantOf(cachedInputTable)) {
            cachedRefBtn = findFirstButton(cachedInputTable);
        }

        float bw = cachedRefBtn != null && cachedRefBtn.getWidth() > 8f ? cachedRefBtn.getWidth() : 48f;
        float bh = cachedRefBtn != null && cachedRefBtn.getHeight() > 8f ? cachedRefBtn.getHeight() : 48f;
        cachedYBtn.setSize(bw, bh);

        float targetX, targetY;
        if (cachedRefBtn != null && cachedRefBtn.getWidth() > 8f) {
            Vec2 refPos = cachedRefBtn.localToStageCoordinates(tmpStageVec.set(0f, 0f));
            targetX = refPos.x - bw - 4f;
            targetY = refPos.y;
        } else {
            Vec2 bl = cachedInputTable.localToStageCoordinates(tmpStageVec.set(0f, 0f));
            targetX = bl.x - bw - 4f;
            targetY = bl.y + 2f;
        }
        targetX = Math.max(4f, targetX);
        cachedYBtn.setPosition(targetX, targetY, Align.bottomLeft);
    }

    public static void closeAttached() {
        if (attachedMonitor != null) {
            try {
                attachedMonitor.removeFromScene();
            } catch (Throwable t) {
                warnOnce("收起内嵌面板", t);
            }
            attachedMonitor = null;
        }
        attachedBuilding = null;
    }

    private static class DragPreview extends Element {
        private final float w, h;
        private final String title;

        DragPreview(float w, float h, String title) {
            this.w = w;
            this.h = h;
            this.title = title;
            touchable = Touchable.disabled;
        }

        @Override
        public void draw() {
            float mx = Yr2Vars.mouseX();
            float my = Yr2Vars.mouseY();

            float rx = mx - w / 2f;
            float ry = my - h / 2f;
            Draw.color(Pal.gray, 0.45f);
            Fill.crect(rx, ry, w, h);
            Lines.stroke(4f, Pal.gray);
            Lines.rect(rx, ry, w, h);
            Lines.stroke(2f, Yr2Vars.themeColor);
            Lines.rect(rx, ry, w, h);
            Draw.reset();

            Fonts.outline.draw(title, mx, ry + h + 22f, Yr2Vars.themeColor, 0.45f, false, Align.center);
        }
    }
}
