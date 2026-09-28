package yr2lm.ui;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.fragments.BlockConfigFragment;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;

import java.lang.reflect.Field;

/**
 * 原生方块配置栏注入器:
 * 在逻辑块、信息板与内存块配置条中追加设置齿轮:
 * - 单击: 在配置条正下方展开/收起内嵌监视面板，支持右下角微型手柄自由缩放与偏好记忆；
 * - 拖拽: 拖拽齿轮实时显示等比预览框，松手在目标位置创建独立悬浮窗口；
 * - 原版内存块已统一为可配置，享受一致的交互流程。
 */
public class ConfigInjector {
    private static Field tableField;
    private static boolean inited = false;

    /** 当前内嵌贴附的方块、监视器与独立宿主容器。 */
    private static Building attachedBuilding = null;
    private static Monitor attachedMonitor = null;
    private static Table attachedHost = null;

    /** 独立浮窗尺寸记忆 (齿轮拖拽创建 / 主界面 add), 按方块类型持久化。 */
    public static float lastLogicW = -1f, lastLogicH = -1f;
    public static float lastMemW = -1f, lastMemH = -1f;
    public static float lastMesW = -1f, lastMesH = -1f;
    /** 内嵌贴附面板独立尺寸记忆 (单击齿轮展开) — 与浮窗完全分离, 改一个不影响另一个。 */
    public static float paneLogicW = -1f, paneLogicH = -1f;
    public static float paneMemW = -1f, paneMemH = -1f;
    public static float paneMesW = -1f, paneMesH = -1f;

    /** 独立浮窗偏好尺寸。 */
    public static float[] prefSize(Building selected) {
        return sizeFor(selected, false);
    }

    /** 内嵌贴附面板偏好尺寸。 */
    public static float[] paneSize(Building selected) {
        return sizeFor(selected, true);
    }

    /**
     * 各方块监视窗口的偏好尺寸: 有记忆用记忆, 无记忆按方块类型给默认值。
     * 浮窗与内嵌面板各寄一套记忆, 三处创建入口 (浮窗创建/齿轮拖拽预览/内嵌面板) 统一走此方法。
     */
    private static float[] sizeFor(Building selected, boolean pane) {
        float w, h;
        if (selected instanceof LogicBlock.LogicBuild) {
            float defaultLogicW = Core.graphics.getWidth() >= 860f ? 800f : 540f;
            w = pane ? (paneLogicW > 0 ? paneLogicW : defaultLogicW) : (lastLogicW > 0 ? lastLogicW : defaultLogicW);
            h = pane ? (paneLogicH > 0 ? paneLogicH : 520f) : (lastLogicH > 0 ? lastLogicH : 520f);
        } else if (selected instanceof MemoryBlock.MemoryBuild) {
            w = pane ? (paneMemW > 0 ? paneMemW : 930f) : (lastMemW > 0 ? lastMemW : 930f);
            h = pane ? (paneMemH > 0 ? paneMemH : 460f) : (lastMemH > 0 ? lastMemH : 460f);
        } else {
            w = pane ? (paneMesW > 0 ? paneMesW : 460f) : (lastMesW > 0 ? lastMesW : 460f);
            h = pane ? (paneMesH > 0 ? paneMesH : 300f) : (lastMesH > 0 ? lastMesH : 300f);
        }
        return new float[]{Math.min(w, Core.graphics.getWidth() - 20f), Math.min(h, Core.graphics.getHeight() - 40f)};
    }

    /** 拖拽齿轮时的落点预览元素。 */
    private static DragPreview dragPreview = null;

    static {
        try {
            tableField = BlockConfigFragment.class.getDeclaredField("table");
            tableField.setAccessible(true);
        } catch (Throwable ignored) {}
    }

    public static void init() {
        if (inited) return;
        inited = true;
        Events.run(EventType.Trigger.update, ConfigInjector::update);
    }

    public static void update() {
        if (!Vars.state.isGame()) {
            closeAttached();
            return;
        }

        checkPlacementUI();

        var config = Vars.control == null || Vars.control.input == null ? null : Vars.control.input.config;

        //生命周期完全跟随原生配置面板: 原生配置面板关闭或切换方块时，自动收起并释放内嵌面板
        if (attachedBuilding != null) {
            boolean alive = config != null && config.isShown() && config.getSelected() == attachedBuilding;
            if (!alive) {
                closeAttached();
            }
        }

        if (config == null || !config.isShown()) return;
        Building selected = config.getSelected();
        if (selected == null) return;

        //仅对支持的方块(逻辑块、信息板、内存块)注入按钮
        if (!(selected instanceof LogicBlock.LogicBuild
                || selected instanceof MessageBlock.MessageBuild
                || selected instanceof MemoryBlock.MemoryBuild)) return;

        Table configTable = getConfigTable();
        if (configTable == null) return;

        //如果当前配置表或其子工具条中尚未注入按钮，则安全追加
        if (configTable.find("yr2lm-config-btn") == null) {
            injectButton(configTable, selected);
        }
    }

    private static Table getConfigTable() {
        try {
            var config = Vars.control.input.config;
            if (tableField != null) {
                Table t = (Table) tableField.get(config);
                return t != null && t.visible ? t : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 智能定位按钮应该追加的表格容器:
     * - 在 X 端等改端中，第一行按钮被收纳在子 Table 里 (如 LogicSupport 工具栏)，需追加进该子 Table，防止掉入第二行末尾；
     * - 在原版中，按钮直接平铺在 configTable 中，直接追加到 configTable。
     */
    private static Table findButtonTargetTable(Table configTable) {
        for (Element child : configTable.getChildren()) {
            if (child instanceof Table subTable) {
                if (hasButton(subTable)) {
                    return subTable;
                }
            }
        }
        return configTable;
    }

    private static boolean hasButton(Table table) {
        for (Element e : table.getChildren()) {
            if (e instanceof Button) return true;
        }
        return false;
    }

    private static void injectButton(Table configTable, Building selected) {
        Table targetTable = findButtonTargetTable(configTable);
        if (configTable.find("yr2lm-config-btn") != null) return;

        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.cleari);
        style.imageUp = Icon.settings;

        ImageButton injectBtn = new ImageButton(style);
        injectBtn.name = "yr2lm-config-btn";

        //手势判定: 点击展开/收起内嵌面板; 拖拽呈现指引连线并在松手处直接创建独立浮窗
        injectBtn.addListener(new InputListener() {
            float startX, startY;
            boolean dragging = false;
            static final float DRAG_THRESHOLD = 14f;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startX = Core.input.mouseX();
                startY = Core.input.mouseY();
                dragging = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!dragging) {
                    float dx = Core.input.mouseX() - startX;
                    float dy = Core.input.mouseY() - startY;
                    if (dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        dragging = true;
                        // 已有监视窗口时预览其真实尺寸, 否则按偏好/默认尺寸
                        Monitor existing = Yr2Vars.combination.getMonitor(selected);
                        float[] sz = (existing != null) ? new float[]{existing.size.x, existing.size.y} : prefSize(selected);
                        dragPreview = new DragPreview(sz[0], sz[1], selected.block.name);
                        Core.scene.root.addChild(dragPreview);
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
                    // 拖拽齿轮释放: 在松手终点直接创建独立浮动窗口并播放平滑弹出缓动动画!
                    closeAttached();
                    Monitor m = Yr2Vars.combination.openFloating(selected, Core.input.mouse());
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
                    try {
                        Vars.control.input.config.hideConfig();
                    } catch (Throwable ignored) {}
                } else {
                    // 点击齿轮: 展开/收起内嵌面板
                    toggleAttached(configTable, selected);
                }
                dragging = false;
            }
        });

        //在第一排功能按钮末尾紧凑追加，保持原色，不搞变蓝特效
        targetTable.add(injectBtn).size(40f);
        targetTable.pack();
        configTable.pack();
    }

    private static void toggleAttached(Table configTable, Building selected) {
        //如果当前已为该方块展开面板，则点击收起
        if (attachedBuilding == selected && attachedHost != null && attachedHost.hasParent()) {
            closeAttached();
            return;
        }

        //清理前一个内嵌面板
        closeAttached();

        attachedBuilding = selected;

        if (selected instanceof LogicBlock.LogicBuild lb) {
            attachedMonitor = new LogicMonitor(lb.block.name, lb, Core.input.mouse());
        } else if (selected instanceof MessageBlock.MessageBuild mb) {
            attachedMonitor = new MessageMonitor(mb.block.name, mb, Core.input.mouse());
        } else if (selected instanceof MemoryBlock.MemoryBuild mb) {
            attachedMonitor = new MemoryMonitor(mb.block.name, mb, Core.input.mouse());
        } else {
            return;
        }
        // 内嵌贴附模式: 尺寸记忆与独立浮窗分离, 改一个不影响另一个
        attachedMonitor.attachedPaneMode = true;
        float[] sz = paneSize(selected);
        attachedMonitor.size.set(sz[0], sz[1]);

        //构建独立自对齐宿主容器: 纯内容呈现，无外壳、无标题栏
        attachedHost = new Table();
        attachedHost.name = "yr2lm-attached-pane";
        attachedHost.background(Styles.black8);
        attachedHost.margin(4f);

        // 右下角微型拖拽缩放手柄 (Corner Drag-Resize)
        Table gripTable = new Table();
        gripTable.bottom().right();

        ImageButton.ImageButtonStyle gripStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
        gripStyle.imageUpColor = Yr2Vars.mutedColor;
        ImageButton grip = new ImageButton(Icon.resizeSmall, gripStyle);
        grip.addListener(new InputListener() {
            float startX, startY;
            float startW, startH;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startX = Core.input.mouseX();
                startY = Core.input.mouseY();
                startW = attachedHost.getWidth();
                startH = attachedHost.getHeight();
                gripStyle.imageUpColor = Yr2Vars.themeColor;
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float dx = Core.input.mouseX() - startX;
                float dy = Core.input.mouseY() - startY;

                // 水平居中锚定: 右移 dx 则整体加宽 2*dx
                float newW = Math.max(360f, startW + dx * 2f);
                // 纵向: 固定贴附在下方，向下拖 (dy < 0) 增加高度
                float newH = Math.max(200f, startH - dy);

                attachedHost.setSize(newW, newH);
                if (attachedMonitor != null) {
                    attachedMonitor.size.set(newW, newH);
                    // 触发布局重排与分栏比例迁移 (与浮窗手柄 onResized 行为一致)
                    attachedMonitor.onResized();
                }
                if (selected instanceof LogicBlock.LogicBuild) {
                    paneLogicW = newW;
                    paneLogicH = newH;
                } else if (selected instanceof MemoryBlock.MemoryBuild) {
                    paneMemW = newW;
                    paneMemH = newH;
                } else if (selected instanceof MessageBlock.MessageBuild) {
                    paneMesW = newW;
                    paneMesH = newH;
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                gripStyle.imageUpColor = Yr2Vars.mutedColor;
            }
        });

        gripTable.add(grip).size(26f).pad(0f, 0f, 2f, 2f).bottom().right();
        attachedHost.stack(attachedMonitor.mainTable, gripTable).grow();
        attachedHost.setSize(sz[0], sz[1]);

        //展开平滑缓动动画 (由顶部自然展开)
        attachedHost.setTransform(true);
        attachedHost.setOrigin(Align.top);
        attachedHost.setScale(1f, 0f);
        attachedHost.actions(
            Actions.scaleTo(1f, 1f, 0.12f, Interp.pow3Out),
            Actions.run(() -> attachedHost.setTransform(false))
        );

        //每帧跟随配置条底部居中对齐，紧贴配置条下方居中对齐
        attachedHost.update(() -> {
            if (configTable != null && configTable.parent != null) {
                float anchorX = configTable.x + configTable.getWidth() / 2f;
                float anchorY = configTable.y - 4f;
                attachedHost.setPosition(anchorX, anchorY, Align.top);
            }
        });

        //作为独立兄弟组件挂载到配置条所在的父容器下 (在同一图层，生命周期由 update 销毁控制)
        if (configTable.parent != null) {
            configTable.parent.addChild(attachedHost);
        } else {
            Core.scene.add(attachedHost);
        }
    }

    /** 检查并在右下角建筑/蓝图栏注入 "y" 图标按钮，用于在主窗口隐藏时重新唤出。 */
    private static void checkPlacementUI() {
        Element found = Core.scene == null ? null : Core.scene.find("inputTable");
        if (found instanceof Table inputTable && inputTable.find("yr2lm-reopen-button") == null) {
            inputTable.button(b -> {
                b.center();
                b.label(() -> "[#" + Yr2Vars.themeHex + "]y[]").style(Styles.outlineLabel);
            }, Styles.clearNonei, () -> {
                Yr2Vars.combination.hidden = !Yr2Vars.combination.hidden;
                if (!Yr2Vars.combination.hidden) {
                    Yr2Vars.combination.bringToFocus();
                }
            }).name("yr2lm-reopen-button").size(48f).visible(() -> Vars.state.isGame() && Yr2Vars.combination.hidden);
        }
    }

    /** 安全收起并销毁内嵌面板，确保恢复被暂停的方块执行。 */
    public static void closeAttached() {
        if (attachedMonitor != null) {
            try {
                //若处于单步/暂停态，恢复方块执行，防止处理器被永久卡住
                attachedMonitor.removeFromScene();
            } catch (Throwable ignored) {}
                attachedMonitor = null;
        }
        if (attachedHost != null) {
            attachedHost.remove();
            attachedHost = null;
        }
        attachedBuilding = null;
    }

    /** 拖拽齿轮时的落点预览阴影与外框。 */
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
            float mx = Core.input.mouseX();
            float my = Core.input.mouseY();

            // 绘制窗口外框预览与半透明底色 (与实际窗口尺寸完全一致)
            float rx = mx - w / 2f;
            float ry = my - h / 2f;
            Draw.color(Pal.gray, 0.45f);
            Fill.crect(rx, ry, w, h);
            Lines.stroke(4f, Pal.gray);
            Lines.rect(rx, ry, w, h);
            Lines.stroke(2f, Yr2Vars.themeColor);
            Lines.rect(rx, ry, w, h);
            Draw.reset();

            // 标题文字微标
            Fonts.outline.draw(title, mx, ry + h + 22f, Yr2Vars.themeColor, 0.45f, false, Align.center);
        }
    }
}
