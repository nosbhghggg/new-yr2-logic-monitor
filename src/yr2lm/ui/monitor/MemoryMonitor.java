package yr2lm.ui.monitor;

import arc.Core;
import arc.func.Intc;
import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.FocusListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.MemoryBlock;
import yr2lm.Yr2Vars;
import yr2lm.graphics.DrawExt;
import yr2lm.ui.window.Monitor;
import yr2lm.ui.window.Yrailiuxa2;
import yr2lm.util.MemUtil;

import java.util.Arrays;
import java.util.Objects;

import static arc.scene.ui.TextField.TextFieldFilter.digitsOnly;

/**
 * 内存块 (Memory Cell / Memory Bank) 监视与编辑器。
 */
public class MemoryMonitor extends Monitor {
    private static final Color GUIDE = Yr2Vars.themeColor;
    private static final Color FLASH_COLOR = Yr2Vars.dataChangeColor;

    private final MemoryBlock.MemoryBuild memoryBuild;
    private int start = 0, end, col = 8, decimals = 4;
    private boolean editMode = false;
    private int activeSingleEditIndex = -1;
    private Object[] memoryBuf;
    private final String[] textCache;
    private final Object[] valueCache;
    private final float[] flashTimers;
    private final CellLabel[] cellPool;
    private boolean initializedValues = false;
    private int cacheDecimals = Integer.MIN_VALUE;

    private final Table memTools;
    private final Table gridTable;
    private final ScrollPane gridScroll;

    @SuppressWarnings("this-escape")
    public MemoryMonitor(String s, MemoryBlock.MemoryBuild memoryBuild, Vec2 pos, float initW, float initH) {
        super(s, memoryBuild, pos);
        this.memoryBuild = memoryBuild;
        size.set(initW, initH);
        minSize.set(Math.min(340f, initW), 220f);

        end = MemUtil.capacity(memoryBuild);
        textCache = new String[end];
        valueCache = new Object[end];
        flashTimers = new float[end];
        cellPool = new CellLabel[end];
        Arrays.fill(textCache, "");
        for (int i = 0; i < end; i++) {
            final int idx = i;
            CellLabel lbl = new CellLabel(idx, () -> textCache[idx]);
            lbl.setAlignment(Align.right);
            lbl.setEllipsis(true);
            lbl.userObject = idx;
            lbl.clicked(() -> {
                activeSingleEditIndex = idx;
                rebuildGridRows();
            });
            cellPool[i] = lbl;
        }
        refreshTexts();

        memTools = new Table();
        gridTable = new Table();
        gridTable.top();
        gridScroll = new ScrollPane(gridTable);
        Yrailiuxa2.configurePane(gridScroll);
        gridScroll.update(() -> {
            Yr2Vars.bindScrollFocus(gridScroll);
            if (editMode) return;
            int lo = loBound(), hi = hiBound();
            for (int i = lo; i < hi; i++) {
                if (flashTimers[i] > 0f) {
                    flashTimers[i] = Math.max(0f, flashTimers[i] - Time.delta / 24f);
                }
            }
            refreshTexts();
            drawGuideFor(Yr2Vars.hoverElement());
        });

        memToolsBuild();
        init();
    }

    private int loBound() {
        return Math.max(0, Math.min(start, end));
    }

    private int hiBound() {
        return Math.min(textCache.length, Math.max(start, end));
    }

    @Override
    public void init() {
        rebuildGridRows();
        monitorTable.clear();
        monitorTable.top();
        monitorTable.add(memTools).minWidth(0f).growX();
        monitorTable.row();
        monitorTable.add(gridScroll).minWidth(0f).minHeight(0f).grow();
    }

    private void memToolsBuild() {
        memTools.clear();
        memTools.table(t -> {
            t.add(boundField(start, v -> start = v)).width(60).pad(0, 3, 0, 3);
            t.add(boundField(end, v -> end = v)).width(60).pad(0, 3, 0, 3);
            t.add(dualInput(0, 10, decimals, v -> decimals = v)).minWidth(0f).grow();
            t.add(dualInput(1, 16, col, v -> {
                if (v == col) return;
                col = v;
                rebuildGridRows();
            })).minWidth(0f).grow();

            ImageButton editBtn = t.button(Icon.edit, Styles.emptyi, () -> {
                activeSingleEditIndex = -1;
                if (editMode) {
                    MemUtil.apply(memoryBuild, memoryBuf);
                    editMode = false;
                } else {
                    memoryBuf = MemUtil.snapshot(memoryBuild);
                    editMode = true;
                }
                rebuildGridRows();
            }).size(46).pad(0, 3, 0, 3).get();
            // 直接操作 editBtn.getStyle().imageUp，确保编辑/保存图标即时切换
            editBtn.update(() -> editBtn.getStyle().imageUp = editMode ? Icon.save : Icon.edit);
        }).height(40).minWidth(0f).growX();
    }

    private TextField boundField(int value, Intc setter) {
        TextField field = new TextField(String.valueOf(value));
        field.setFilter(digitsOnly);
        field.changed(() -> {
            try {
                setter.get(Mathf.clamp(Integer.parseInt(field.getText()), 0, MemUtil.capacity(memoryBuild)));
            } catch (NumberFormatException ignored) {
            }
        });
        field.keyDown(KeyCode.enter, this::rebuildGridRows);
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusListener.FocusEvent event, Element actor, boolean focused) {
                if (!focused) rebuildGridRows();
            }
        });
        return field;
    }

    private Table dualInput(float min, float max, int initial, Intc onChange) {
        Table box = new Table();
        Slider slider = new Slider(min, max, 1f, false);
        slider.setValue(initial);
        TextField field = new TextField(String.valueOf(initial));
        field.setFilter(digitsOnly);
        boolean[] syncing = {false};
        slider.changed(() -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                int v = (int) slider.getValue();
                String sv = String.valueOf(v);
                if (!field.getText().equals(sv)) field.setText(sv);
                onChange.get(v);
            } finally {
                syncing[0] = false;
            }
        });
        field.changed(() -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                int v = (int) Mathf.clamp(Integer.parseInt(field.getText()), min, max);
                if ((int) slider.getValue() != v) slider.setValue(v);
                onChange.get(v);
            } catch (NumberFormatException ignored) {
            } finally {
                syncing[0] = false;
            }
        });
        box.add(slider).growX().minWidth(0f).pad(0, 4, 0, 4);
        box.add(field).width(44);
        return box;
    }

    private void rebuildGridRows() {
        gridTable.clearChildren();
        gridTable.top();
        int startIndex = loBound();
        int eBound = hiBound();
        int cellCounter = 0;
        for (int i = startIndex; i < eBound; i++, cellCounter++) {
            final int index = i;
            if (editMode) {
                gridTable.field(MemUtil.textOf(memoryBuf[index]), s -> memoryBuf[index] = MemUtil.parseValue(s))
                        .minWidth(0f).growX().pad(0, 5, 0, 5);
            } else if (activeSingleEditIndex == index) {
                Object curVal = MemUtil.read(memoryBuild, index);
                TextField inlineField = gridTable.field(MemUtil.textOf(curVal), s -> {}).minWidth(0f).growX().pad(0, 5, 0, 5).get();
                Runnable commitSingle = () -> {
                    if (activeSingleEditIndex == index) {
                        MemUtil.write(memoryBuild, index, MemUtil.parseValue(inlineField.getText()));
                        activeSingleEditIndex = -1;
                        refreshTexts();
                        rebuildGridRows();
                    }
                };
                inlineField.keyDown(KeyCode.enter, commitSingle);
                inlineField.keyDown(KeyCode.escape, () -> {
                    activeSingleEditIndex = -1;
                    rebuildGridRows();
                });
                inlineField.addListener(new FocusListener() {
                    @Override
                    public void keyboardFocusChanged(FocusEvent event, Element actor, boolean focused) {
                        if (!focused) commitSingle.run();
                    }
                });
                if (Core.scene != null) Core.scene.setKeyboardFocus(inlineField);
            } else {
                gridTable.add(cellPool[index]).height(35).minWidth(0f).growX().pad(0, 5, 0, 5);
            }
            if (cellCounter % col == col - 1 || i == eBound - 1) {
                gridTable.labelWrap("[#" + Yr2Vars.mutedHex + "]#" + index).size(60, 40).pad(0, 10, 0, 5);
                gridTable.row();
            }
        }
    }

    private void refreshTexts() {
        boolean reDecimal = cacheDecimals != decimals;
        if (reDecimal) cacheDecimals = decimals;
        int lo = loBound(), hi = hiBound();
        for (int i = lo; i < hi; i++) {
            Object v = MemUtil.read(memoryBuild, i);
            if (!Objects.equals(v, valueCache[i])) {
                if (initializedValues) {
                    flashTimers[i] = 1.0f;
                }
                valueCache[i] = v;
                textCache[i] = MemUtil.textOf(v, decimals);
            } else if (reDecimal) {
                textCache[i] = MemUtil.textOf(v, decimals);
            }
        }
        initializedValues = true;
    }

    private void drawGuideFor(Element hovered) {
        for (Element e = hovered; e != null; e = e.parent) {
            if (!(e instanceof Label) || !(e.userObject instanceof Integer index)) continue;
            Object v = (index >= 0 && index < valueCache.length) ? valueCache[index] : MemUtil.read(memoryBuild, index);
            if (!(v instanceof Unit || v instanceof Building)) return;
            Vec2 from = arc.util.Tmp.v1.set(memoryBuild.x, memoryBuild.y);
            Vec2 mouse = arc.util.Tmp.v2.set(Core.input.mouse());
            if (v instanceof Unit u) {
                DrawExt.info(from, u, "#" + index, GUIDE);
                DrawExt.screenWorldLine(mouse, u, GUIDE);
            } else {
                Building b = (Building) v;
                DrawExt.info(from, b, "#" + index, GUIDE);
                DrawExt.screenWorldLine(mouse, b, GUIDE);
            }
            return;
        }
    }

    private class CellLabel extends Label {
        private final int index;

        CellLabel(int index, Prov<CharSequence> sup) {
            super(sup);
            this.index = index;
        }

        @Override
        public void draw() {
            float t = (flashTimers != null && index >= 0 && index < flashTimers.length) ? flashTimers[index] : 0f;
            if (t > 0f) {
                Draw.color(FLASH_COLOR, t * 0.28f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                color.set(Color.white).lerp(FLASH_COLOR, t);
            } else {
                color.set(Color.white);
            }
            super.draw();
            if (t > 0f) {
                Draw.reset();
            }
        }

        @Override
        public void invalidateHierarchy() {
            invalidate();
        }
    }

    @Override
    public MemoryBlock.MemoryBuild getBuilding() {
        return memoryBuild;
    }
}
