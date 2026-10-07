package yr2lm.ui;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
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

    /** 拖拽齿轮时的落点预览元素。 */
    private static DragPreview dragPreview = null;

    /** 借游戏内部结构接手的几处一旦变形就会静默失灵: 每个位置只往游戏日志记一次, 免得每帧刷。 */
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
        } catch (Throwable t) {
            warnOnce("读游戏配置表", t);
        }
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
                        float[] sz = (existing != null) ? new float[]{existing.size.x, existing.size.y} : MonitorFactory.prefSize(selected);
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
                    Vars.control.input.config.hideConfig();
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

        attachedMonitor = MonitorFactory.create(selected, Core.input.mouse(), true);
        if (attachedMonitor == null) return;

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
                    // 触发布局重排、分栏比例迁移与尺寸记忆持久化 (onResized 内按 attachedPaneMode 写 pane* 记忆)
                    attachedMonitor.onResized();
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                gripStyle.imageUpColor = Yr2Vars.mutedColor;
            }
        });

        gripTable.add(grip).size(26f).pad(0f, 0f, 2f, 2f).bottom().right();
        attachedHost.stack(attachedMonitor.mainTable, gripTable).grow();
        attachedHost.setSize(attachedMonitor.size.x, attachedMonitor.size.y);

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
    /** 递归取容器内第一个原生按钮 (跳过自身浮标), 供 y 浮标做尺寸自适应。 */
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
        Element found = Core.scene == null ? null : Core.scene.find("inputTable");
        if (!(found instanceof Table inputTable)) {
            // 原生选择篮不存在(菜单/未开局): 浮标一并移除
            Element orphan = Core.scene.find("yr2lm-reopen-button");
            if (orphan != null) orphan.remove();
            return;
        }

        // y 浮标挂场景根而非 inputTable 布局流: 48px 按钮塞进原生多行表格会撑高网格行,
        // 在 MindustryX 自定义选择篮宽高时放大为分类框空白(天坑 #21)。挂根后每帧贴位, 布局零干扰。
        Element existing = Core.scene.find("yr2lm-reopen-button");
        if (existing == null) {
            Button yBtn = new Button(Styles.clearNonei);
            yBtn.name = "yr2lm-reopen-button";
            Label yLbl = new Label(() -> "[#" + Yr2Vars.themeHex + "]y[]");
            yLbl.setStyle(Styles.outlineLabel);
            yLbl.setAlignment(Align.center);
            yBtn.add(yLbl).center().grow();
            yBtn.setSize(48f, 48f);
            yBtn.tapped(() -> {
                Yr2Vars.combination.hidden = !Yr2Vars.combination.hidden;
                if (!Yr2Vars.combination.hidden) {
                    Yr2Vars.combination.bringToFocus();
                }
            });
            yBtn.visible(() -> Vars.state.isGame() && Yr2Vars.combination.hidden
                    && Core.scene.find("inputTable") != null);
            Core.scene.add(yBtn);
        }

        // 每帧贴位 + 尺寸自适应: 取选择篮内第一个原生按钮的实际尺寸, y 浮标与旁边的按钮永远等大
        // (MindustryX 调整宽高时原生按钮尺寸可能变, 每帧读即自动跟随)
        Element yBtn = Core.scene.find("yr2lm-reopen-button");
        if (yBtn != null) {
            Button ref = findFirstButton(inputTable);
            float bw = ref != null && ref.getWidth() > 8f ? ref.getWidth() : 48f;
            float bh = ref != null && ref.getHeight() > 8f ? ref.getHeight() : 48f;
            yBtn.setSize(bw, bh);
            // inputTable.x/y 是父容器相对坐标 — MindustryX 改造过 HUD 后并不等于屏幕坐标;
            // 必须经 localToStageCoordinates 走完整变换链取真实屏幕位置 (天坑 #22)
            arc.math.geom.Vec2 bl = inputTable.localToStageCoordinates(new arc.math.geom.Vec2(0f, 0f));
            arc.math.geom.Vec2 tr = inputTable.localToStageCoordinates(new arc.math.geom.Vec2(inputTable.getWidth(), inputTable.getHeight()));
            yBtn.setPosition(tr.x - bw - 2f, bl.y + 2f, Align.bottomLeft);
        }
    }

    /** 安全收起并销毁内嵌面板，确保恢复被暂停的方块执行。 */
    public static void closeAttached() {
        if (attachedMonitor != null) {
            try {
                //若处于单步/暂停态，恢复方块执行，防止处理器被永久卡住
                attachedMonitor.removeFromScene();
            } catch (Throwable t) {
                //这里不能中断后面的清理(否则内嵌面板会永久卡住), 但也不能一声不吭
                warnOnce("收起内嵌面板", t);
            }
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
