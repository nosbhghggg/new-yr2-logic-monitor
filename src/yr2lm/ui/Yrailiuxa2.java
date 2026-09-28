package yr2lm.ui;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import yr2lm.Yr2Vars;

/**
 * 监视窗口基类。
 * <p>
 * 核心架构特性:
 * 1. 窗口生命周期与世界投影锚定 (Pinned to World): 锚点居于窗口顶部居中 (Top-Center)，图层置于 HUD 之下不遮挡玩家 UI；
 * 2. 缩放职责彻底解耦: 右下角微型手柄专司自由拉伸尺寸，右上角按钮专司【全息随镜缩放 / 1:1 UI 像素锁定】模式切换；
 * 3. 彻底废除 30px 黑粗边框，界面纯净通透；
 * 4. 防焦点吞噬拖拽判定与按比例屏幕自适应迁移。
 */
public class Yrailiuxa2 extends Table {
    private final Table headTable;
    private final Table gripTable;
    protected final Table mainTable;
    protected final Vec2 pos, size, bias;
    protected final Vec2 minSize;
    public boolean hidden = false;

    /** 是否固定在世界上（世界坐标系，跟随地图移动与缩放）。 */
    public boolean pinned = false;

    /** 内嵌贴附模式 (单击齿轮展开的原生配置条下方面板): 尺寸记忆与独立浮窗完全分离, 互不污染。 */
    public boolean attachedPaneMode = false;

    /** 钉在世界上时是否锁定为固定 1:1 UI 像素大小 (不跟随镜头视野缩放)。默认 false (全息随镜)。 */
    public boolean lockScale1to1 = false;

    /** 窗口钉在世界上时的世界坐标（锚定在窗口最上方中间点 Top-Center）。 */
    protected final Vec2 worldPos = new Vec2();

    /** 内部复用的计算临时向量，避免被全局 tmpVector 污染覆盖。 */
    private final Vec2 tmpVec = new Vec2();

    /** 是否提供图钉按钮。 */
    protected boolean pinnable;

    private Runnable onPinChanged;
    public Runnable onClose;
    private boolean showMainTable = true;
    private float lastScreenW = -1f, lastScreenH = -1f;
    private float baseCameraScale = -1f;

    public Yrailiuxa2(String text) {
        this(text, true);
    }

    @SuppressWarnings("this-escape")
    public Yrailiuxa2(String text, boolean pinnable) {
        this.pinnable = pinnable;
        name = text;
        headTable = new Table();
        mainTable = new Table();
        mainTable.top().left();
        gripTable = new Table();

        pos = new Vec2();
        size = new Vec2();
        bias = new Vec2();
        minSize = new Vec2();

        background(Styles.black8);
        headTableInit(text);
        gripTableInit();
        mainTableBuild();

        touchable = Touchable.enabled;
        addListener(new InputListener() {
            float downX, downY;

            @Override
            public boolean touchDown(InputEvent event, float xDown, float yDown, int pointer, KeyCode button) {
                bringToFocus();
                if (!blankDraggable(event.targetActor)) return false;
                downX = xDown;
                downY = yDown;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float xDragged, float yDragged, int pointer) {
                pos.add(xDragged - downX, yDragged - downY);
                if (pinned) syncWorldPos();
            }
        });

        update(() -> {
            float screenW = Core.graphics.getWidth(), screenH = Core.graphics.getHeight();
            if (pinned && Core.camera != null) {
                float currentCameraScale = Core.graphics.getWidth() / Core.camera.width;
                if (!lockScale1to1) {
                    if (baseCameraScale <= 0f) {
                        baseCameraScale = currentCameraScale;
                    }
                    float scl = Mathf.clamp(currentCameraScale / baseCameraScale, 0.2f, 4.0f);
                    setTransform(true);
                    setScale(scl);
                } else {
                    if (scaleX != 1f || scaleY != 1f) {
                        setScale(1f);
                        setTransform(false);
                    }
                    baseCameraScale = -1f;
                }

                setOrigin(size.x / 2f, showMainTable ? size.y : 30f);
                tmpVec.set(worldPos);
                Core.camera.project(tmpVec);
                pos.set(tmpVec.x - size.x / 2f, tmpVec.y - size.y);

                if (parent != null && Vars.ui != null && Vars.ui.hudGroup != null && Vars.ui.hudGroup.parent == parent) {
                    int hudZ = Vars.ui.hudGroup.getZIndex();
                    if (getZIndex() >= hudZ) {
                        setZIndex(Math.max(0, hudZ - 1));
                    }
                }
            } else {
                if (scaleX != 1f || scaleY != 1f) {
                    setScale(1f);
                    setTransform(false);
                }
                baseCameraScale = -1f;

                if (lastScreenW > 0f && (screenW != lastScreenW || screenH != lastScreenH)) {
                    pos.x *= screenW / lastScreenW;
                    pos.y *= screenH / lastScreenH;
                    size.x = Math.min(size.x, screenW - 60f);
                    size.y = Math.min(size.y, screenH - 60f);
                    minSize.x = Math.min(minSize.x, size.x);
                    minSize.y = Math.min(minSize.y, size.y);
                }
                pos.x = Mathf.clamp(pos.x, 30f - size.x, Core.graphics.getWidth() - 30f);
                pos.y = Mathf.clamp(pos.y, 30f - size.y, Core.graphics.getHeight() - size.y);
            }
            lastScreenW = screenW;
            lastScreenH = screenH;

            if (showMainTable) {
                setPosition(pos.x, pos.y);
                setSize(size.x, size.y);
            } else {
                setPosition(pos.x, pos.y + size.y - 30f);
                setSize(size.x, 30f);
            }
            onUpdate();
        });
    }

    protected void onUpdate() {}

    @Override
    public void draw() {
        // 窗体级裁剪: 极端尺寸下任何内部布局的溢出绘制都被窗口边缘裁住, 绝不污染窗口外的画面
        if (clipBegin()) {
            super.draw();
            clipEnd();
        }
    }

    /** 窗口尺寸被拖拽调节后的回调钩子。 */
    protected void onResized() {}

    public void togglePinned() {
        setPinned(!pinned);
    }

    public void setPinned(boolean p) {
        if (this.pinned == p) return;
        this.pinned = p;
        if (p) {
            syncWorldPos();
            bringToFocus();
            if (!lockScale1to1 && Core.camera != null) {
                baseCameraScale = Core.graphics.getWidth() / Core.camera.width;
            }
        } else {
            toFront();
            if (scaleX != 1f || scaleY != 1f) {
                setScale(1f);
                setTransform(false);
            }
            baseCameraScale = -1f;
        }
        if (onPinChanged != null) onPinChanged.run();
    }

    public void bringToFocus() {
        if (parent == null) return;
        if (pinned) {
            if (Vars.ui != null && Vars.ui.hudGroup != null && Vars.ui.hudGroup.parent == parent) {
                int hudZ = Vars.ui.hudGroup.getZIndex();
                setZIndex(Math.max(0, hudZ - 1));
            } else {
                toBack();
            }
        } else {
            toFront();
        }
    }

    public void syncWorldPos() {
        if (Core.camera == null) return;
        tmpVec.set(pos.x + size.x / 2f, pos.y + size.y);
        Core.camera.unproject(tmpVec);
        worldPos.set(tmpVec);
    }

    private boolean blankDraggable(Element target) {
        ScrollPane blankPane = null;
        for (Element e = target; e != null && e != this; e = e.parent) {
            if (e instanceof Button || e instanceof TextField || e instanceof Slider) return false;
            if (e instanceof ScrollPane pane) {
                if (pane.getMaxX() > 0.001f || pane.getMaxY() > 0.001f) return false;
                blankPane = pane;
            }
        }
        if (blankPane != null) Core.scene.cancelTouchFocus(blankPane);
        return true;
    }

    private void mainTableBuild() {
        clear();
        top();
        add(headTable).height(30).growX();
        if (!showMainTable) return;
        row();
        stack(mainTable, gripTable).grow();
    }

    private void gripTableInit() {
        gripTable.clear();
        gripTable.bottom().right();
        gripTable.touchable = Touchable.childrenOnly;

        ImageButton.ImageButtonStyle gripStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
        gripStyle.imageUpColor = Yr2Vars.mutedColor;
        ImageButton grip = new ImageButton(Icon.resizeSmall, gripStyle);

        grip.addListener(new InputListener() {
            float startX, startY;
            float startW, startH;
            float startPosY;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startX = Core.input.mouseX();
                startY = Core.input.mouseY();
                startW = size.x;
                startH = size.y;
                startPosY = pos.y;
                gripStyle.imageUpColor = Yr2Vars.themeColor;
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float dx = Core.input.mouseX() - startX;
                float dy = Core.input.mouseY() - startY;

                float curScale = (isTransform() && scaleX > 0.01f) ? scaleX : 1f;
                float newW = Math.max(minSize.x, startW + dx / curScale);
                float newH = Math.max(minSize.y, startH - dy / curScale);

                // 悬浮模式向下拉伸: 顶部位置保持不动，底部顺应拖拽方向向下延伸长高
                if (!pinned) {
                    pos.y = startPosY - (newH - startH);
                }

                size.set(newW, newH);
                // pinned: 顶部中心锚点保持钉死, 窗口随尺寸围绕锚点自然伸展 (update 每帧按锚点重算 pos);
                // 绝不可在此 syncWorldPos() — 用变大后的窗口顶边反算锚点会让锚点随尺寸漂移, 越往下拉窗口越往上涨
                if (!pinned) {
                    clampToScreen();
                }
                onResized();
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                gripStyle.imageUpColor = Yr2Vars.mutedColor;
                if (pinned && !lockScale1to1 && Core.camera != null) {
                    baseCameraScale = Core.graphics.getWidth() / Core.camera.width;
                }
            }
        });

        gripTable.add(grip).size(26f).pad(0f, 0f, 2f, 2f).bottom().right();
    }

    private void headTableInit(String text) {
        headTable.touchable = Touchable.enabled;
        headTable.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float xDown, float yDown, int pointer, KeyCode button) {
                bias.set(xDown, yDown);
                bringToFocus();
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float xDragged, float yDragged, int pointer) {
                pos.add(xDragged - bias.x, yDragged - bias.y);
                if (pinned) syncWorldPos();
            }
        });

        Label title = new Label(text);
        title.setAlignment(Align.left);
        title.setEllipsis(true);
        headTable.add(title).pad(0, 10, 0, 10).height(30).minWidth(0).growX();

        if (pinnable) {
            ImageButton.ImageButtonStyle pinStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
            pinStyle.imageUp = Icon.lockOpenSmall;
            Button pinButton = headTable.button(Icon.lockOpenSmall, Styles.emptyi, () -> {}).size(30).right().get();
            Runnable updatePinStyle = () -> {
                pinStyle.imageUp = pinned ? Icon.lockSmall : Icon.lockOpenSmall;
                pinStyle.imageUpColor = pinned ? Yr2Vars.themeColor : Color.white;
                pinButton.setStyle(pinStyle);
            };
            pinButton.clicked(() -> {
                togglePinned();
                updatePinStyle.run();
            });
            onPinChanged = updatePinStyle;
        }

        ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
        Button visButton = headTable.button(Icon.eyeSmall, Styles.emptyi, () -> {}).size(30).right().get();
        visButton.clicked(() -> {
            showMainTable = !showMainTable;
            style.imageUp = showMainTable ? Icon.eyeSmall : Icon.eyeOffSmall;
            visButton.setStyle(style);
            mainTableBuild();
        });

        // 缩放模式切换按钮: 钉住时点击切换【全息随镜缩放 / 1:1 UI 像素锁定】；只变色，彻底废除 30px 黑粗边框
        ImageButton.ImageButtonStyle scaleModeStyle = new ImageButton.ImageButtonStyle(Styles.emptyi);
        scaleModeStyle.imageUp = Icon.zoomSmall;
        Button scaleModeButton = headTable.button(Icon.zoomSmall, Styles.emptyi, () -> {}).size(30).right().get();
        Runnable updateScaleModeStyle = () -> {
            scaleModeStyle.imageUpColor = lockScale1to1 ? Yr2Vars.themeColor : Color.white;
            scaleModeButton.setStyle(scaleModeStyle);
        };
        scaleModeButton.clicked(() -> {
            lockScale1to1 = !lockScale1to1;
            if (!lockScale1to1 && Core.camera != null) {
                baseCameraScale = Core.graphics.getWidth() / Core.camera.width;
            } else {
                baseCameraScale = -1f;
            }
            updateScaleModeStyle.run();
        });

        headTable.button(Icon.cancelSmall, Styles.emptyi, this::requestClose).size(30).right();
    }

    public void requestClose() {
        if (onClose != null) onClose.run();
        else removeFromScene();
    }

    public void addToScene() {
        if (hasParent()) return;
        Core.scene.root.addChild(this);
        visible(() -> Vars.state.isGame() && Vars.ui.hudfrag.shown && !hidden);
        bringToFocus();
    }

    public void removeFromScene() {
        Core.scene.root.removeChild(this);
    }

    public float getActualScreenX() {
        if (isTransform() && scaleX != 1f) {
            return pos.x + originX - originX * scaleX;
        }
        return pos.x;
    }

    public float getActualScreenY() {
        float actualY = showMainTable ? pos.y : (pos.y + size.y - 30f);
        if (isTransform() && scaleY != 1f) {
            return actualY + originY - originY * scaleY;
        }
        return actualY;
    }

    public float getActualScreenWidth() {
        if (isTransform() && scaleX != 1f) {
            return size.x * scaleX;
        }
        return size.x;
    }

    public float getActualScreenHeight() {
        float h = showMainTable ? size.y : 30f;
        if (isTransform() && scaleY != 1f) {
            return h * scaleY;
        }
        return h;
    }

    public void clampToScreen() {
        pos.x = Mathf.clamp(pos.x, 30f - size.x, Core.graphics.getWidth() - 30f);
        pos.y = Mathf.clamp(pos.y, 30f - size.y, Core.graphics.getHeight() - size.y);
    }

    public static void bindScrollFocus(ScrollPane p) {
        Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
        if (e != null && e.isDescendantOf(p)) p.requestScroll();
        else if (p.hasScroll()) Core.scene.setScrollFocus(null);
    }

    public static void configurePane(ScrollPane p) {
        p.setupFadeScrollBars(0.5f, 0.25f);
        p.setFadeScrollBars(true);
        p.setScrollingDisabled(true, false);
    }
}
