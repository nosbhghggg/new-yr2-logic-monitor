package yr2lm.ui;

import arc.Core;
import arc.func.Intc;
import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.FocusListener;
import arc.scene.ui.Label;
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
import yr2lm.util.MemUtil;

import java.util.Arrays;
import java.util.Objects;

import static arc.scene.ui.TextField.TextFieldFilter.digitsOnly;

public class MemoryMonitor extends Monitor {
    /** 引导线与主题高亮颜色。 */
    private static final Color GUIDE = Yr2Vars.themeColor;
    /** 数值变更时的闪烁高亮颜色(明亮金黄)。 */
    private static final Color FLASH_COLOR = Yr2Vars.dataChangeColor;

    private final MemoryBlock.MemoryBuild memoryBuild;
    private int start = 0, end, col = 8, decimals = 4;
    private boolean editMode = false;
    private Object[] memoryBuf;
    /** 每格显示文本缓存: 只有值或精度变化时才重新格式化, 避免每帧对全部格子做一次 BigDecimal 运算。 */
    private final String[] textCache;
    private final Object[] valueCache;
    private final float[] flashTimers;
    private boolean initializedValues = false;
    private int cacheDecimals = Integer.MIN_VALUE;
    private final Table memTools, nomPage;

    @SuppressWarnings("this-escape")
    public MemoryMonitor(String s, MemoryBlock.MemoryBuild memoryBuild, Vec2 pos) {
        super(s, memoryBuild, pos);
        this.memoryBuild = memoryBuild;
        end = MemUtil.capacity(memoryBuild);
        textCache = new String[end];
        valueCache = new Object[end];
        flashTimers = new float[end];
        Arrays.fill(textCache, "");
        refreshTexts();
        memTools = new Table();
        nomPage = new Table();
        memToolsBuild();
        init();
        //宽大展示(放大1.5倍至 930x460，对齐 JS 版体验): 舒展容纳多列与多位小数，不自动压扁高度
        float w = Math.min(930f, Core.graphics.getWidth() - 20f);
        size.set(w, 460f);
        minSize.set(Math.min(500f, w), 260f);
    }

    @Override
    public void init() {
        nomPageBuild();
        monitorTableBuild();
    }

    private void monitorTableBuild() {
        monitorTable.clear();
        monitorTable.top();
        monitorTable.add(memTools).growX();
        monitorTable.row();
        monitorTable.add(nomPage).grow();
    }

    private void memToolsBuild() {
        memTools.clear();
        //单排工具栏: 起始下标 | 末尾下标 | 保留小数位数(滑条+输入框) | 每行显示个数(滑条+输入框) | 编辑
        memTools.table(t -> {
            t.add(boundField(start, v -> start = v)).width(64).pad(0, 3, 0, 3);
            t.add(boundField(end, v -> end = v)).width(64).pad(0, 3, 0, 3);
            t.add(dualInput(0, 10, decimals, v -> decimals = v)).grow();
            t.add(dualInput(1, 16, col, v -> {
                if (v == col) return;
                col = v;
                nomPageBuild();
            })).grow();
            arc.scene.ui.ImageButton.ImageButtonStyle editStyle = new arc.scene.ui.ImageButton.ImageButtonStyle(Styles.emptyi);
            arc.scene.ui.ImageButton editBtn = t.button(Icon.edit, editStyle, () -> {
                if (editMode) {
                    MemUtil.apply(memoryBuild, memoryBuf);
                    editMode = false;
                } else {
                    memoryBuf = MemUtil.snapshot(memoryBuild);
                    editMode = true;
                }
                init();
            }).size(50).pad(0, 3, 0, 3).get();
            editBtn.update(() -> editStyle.imageUp = editMode ? Icon.save : Icon.edit);
        }).height(40).growX();
    }

    /** 起始/末尾下标输入框: 输入过程中不重建, 失焦后刷新页面。 */
    private TextField boundField(int value, Intc setter) {
        TextField field = new TextField(String.valueOf(value));
        field.setFilter(digitsOnly);
        field.changed(() -> {
            try {
                setter.get(Mathf.clamp(Integer.parseInt(field.getText()), 0, MemUtil.capacity(memoryBuild)));
            } catch (NumberFormatException ignored) {
            }
        });
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusListener.FocusEvent event, Element actor, boolean focused) {
                if (!focused) nomPageBuild();
            }
        });
        return field;
    }

    /**
     * 滑条+输入框双输入(照 JS 版交互), 两者双向联动, 值变化时回调(幂等)。
     * 滑条必须 minWidth(0): ProgressBar 的 prefWidth 在水平方向写死 140,
     * 而 Element 的最小宽度默认等于 prefWidth, 不解除的话窗口缩小时整排会顶出窗口边界。
     */
    private Table dualInput(float min, float max, int initial, Intc onChange) {
        Table box = new Table();
        Slider slider = new Slider(min, max, 1f, false);
        slider.setValue(initial);
        TextField field = new TextField(String.valueOf(initial));
        field.setFilter(digitsOnly);
        slider.changed(() -> {
            int v = (int) slider.getValue();
            if (!field.getText().equals(String.valueOf(v))) field.setText(String.valueOf(v));
            onChange.get(v);
        });
        field.changed(() -> {
            try {
                int v = (int) Mathf.clamp(Integer.parseInt(field.getText()), min, max);
                if ((int) slider.getValue() != v) slider.setValue(v);
                else onChange.get(v);
            } catch (NumberFormatException ignored) {
            }
        });
        box.add(slider).growX().minWidth(0).pad(0, 4, 0, 4);
        box.add(field).width(46);
        return box;
    }

    private void nomPageBuild() {
        nomPage.clear();
        nomPage.table(t -> t.pane(p -> {
            p.top();
            int startIndex = Math.max(0, Math.min(start, end));
            int eBound = Math.min(textCache.length, Math.max(start, end));
            for (int i = startIndex; i < eBound; i++) {
                int index = i;
                if (editMode) {
                    p.field(MemUtil.textOf(memoryBuf[index]), s -> memoryBuf[index] = MemUtil.parseValue(s))
                            .minWidth(0).growX().pad(0, 5, 0, 5);
                } else {
                    //用 userObject 记住所在下标, 供页面级统一做悬浮引导线判定与数值更新闪烁
                    Label label = new CellLabel(index, () -> textCache[index]);
                    label.setAlignment(Align.right);
                    label.setEllipsis(true);
                    label.userObject = index;
                    p.add(label).height(35).minWidth(0).growX().pad(0, 5, 0, 5);
                }
                if (i % col == col - 1 || i == eBound - 1) {
                    p.labelWrap("[#" + Yr2Vars.mutedHex + "]#" + index).size(60, 40).pad(0, 10, 0, 5);
                    p.row();
                }
            }
        }).grow().update(p -> {
            Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
            if (e != null && e.isDescendantOf(p)) p.requestScroll();
            else if (p.hasScroll()) Core.scene.setScrollFocus(null);
            if (editMode) return;
            // 衰减闪烁计时器 (约 0.4 秒淡出完成)
            for (int i = start; i < end; i++) {
                if (flashTimers[i] > 0f) {
                    flashTimers[i] = Math.max(0f, flashTimers[i] - Time.delta / 24f);
                }
            }
            refreshTexts();
            drawGuideFor(e);
        }).with(Yrailiuxa2::configurePane)).grow();
    }

    /** 刷新各格显示文本: 只有值或精度发生变化的格子才重新格式化, 并触发数值修改闪烁。 */
    private void refreshTexts() {
        boolean reDecimal = cacheDecimals != decimals;
        if (reDecimal) cacheDecimals = decimals;
        for (int i = start; i < end; i++) {
            Object v = MemUtil.read(memoryBuild, i);
            if (!Objects.equals(v, valueCache[i])) {
                if (initializedValues) {
                    flashTimers[i] = 1.0f; // 触发数值被修改的高亮闪烁提示
                }
                valueCache[i] = v;
                textCache[i] = MemUtil.textOf(v, decimals);
            } else if (reDecimal) {
                textCache[i] = MemUtil.textOf(v, decimals);
            }
        }
        initializedValues = true;
    }

    /** 非编辑模式下悬浮存放对象的格子时, 画格子到世界实体的引导线(与变量表指示器一致)。 */
    private void drawGuideFor(Element hovered) {
        for (Element e = hovered; e != null; e = e.parent) {
            if (!(e instanceof Label) || !(e.userObject instanceof Integer index)) continue;
            Object v = MemUtil.read(memoryBuild, index);
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

    /**
     * 定高网格专用 Label: 文本变化只重排自身, 不冒泡失效整张表;
     * 并在数值修改时绘制平滑消退的暖金黄背景微光与高亮文字。
     */
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
                // 绘制背景柔和微光
                Draw.color(FLASH_COLOR, t * 0.28f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height - 2f);
                // 文本从明亮金黄色平滑过渡回纯白
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

