package yr2lm.ui.window;

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
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import yr2lm.Yr2Vars;

/**
 * 监视窗口基类 (统一托管【独立浮窗模式】与【配置栏下方内嵌面板模式】)。
 * <p>
 * 核心修复:
 * 1. `pos`、`size`、`minSize` 声明为 `public` (`bias` 保持 `protected`)，解决跨子包 (`ui.inject`) 访问编译错误；
 * 2. 修复 Arc `Table.button(icon, style, listener)` 内部 `new ImageButtonStyle(stylen)` 克隆样式导致外部修改 `style.imageUp` 失效的 Bug:
 *    所有动态切换图标的按钮一律修改 `btn.getStyle().imageUp` 与 `btn.getStyle().imageUpColor`；
 * 3. 三态锁合一按钮 (`Icon.lockOpenSmall` 未锁定 -> `Icon.lockSmall` 锁定并随镜缩放 -> `Icon.zoomSmall` 锁定1:1)。
 */
public class Yrailiuxa2 extends Table {
    /** 三态锁常量: 0 = 未锁定, 1 = 锁定(1:1固定大小), 2 = 锁定并随镜头缩放。 */
    public static final int PIN_NONE = 0;
    public static final int PIN_LOCKED_1TO1 = 1;
    public static final int PIN_LOCKED_ZOOM = 2;

    private final Table headTable;
    private final Table gripTable;
    public final Table mainTable;
    public final Vec2 pos, size;
    protected final Vec2 bias;
    public final Vec2 minSize;
    public boolean hidden = false;

    /** 当前锁定状态 (0: 未锁定, 1: 锁定1:1, 2: 锁定并缩放)。 */
    public int pinState = PIN_NONE;

    /** 是否处于锁定在世界上的状态 (状态 1 或 状态 2)。 */
    public boolean pinned = false;

    /** 内嵌贴附模式 (单击齿轮展开的原生配置条下方面板): 无标题栏、居中对称拉伸、尺寸记忆与浮窗分离。 */
    public boolean attachedPaneMode = false;

    /** 窗口钉在世界上时的世界坐标（锚定在窗口最上方中间点 Top-Center）。 */
    protected final Vec2 worldPos = new Vec2();

    private final Vec2 tmpVec = new Vec2();
    protected boolean pinnable;

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
                if (attachedPaneMode) return false;
                bringToFocus();
                if (!blankDraggable(event.targetActor)) return false;
                downX = xDown;
                downY = yDown;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float xDragged, float yDragged, int pointer) {
                if (attachedPaneMode) return;
                pos.add((xDragged - downX) * scaleX, (yDragged - downY) * scaleY);
                if (pinned) syncWorldPos();
                applyBoundsNow();
            }
        });

        update(() -> {
            if (attachedPaneMode) {
                setSize(size.x, size.y);
                onUpdate();
                return;
            }

            float screenW = Yr2Vars.sceneW(), screenH = Yr2Vars.sceneH();
            if (pinned && Core.camera != null) {
                float currentCameraScale = Core.graphics.getWidth() / Core.camera.width;
                if (pinState == PIN_LOCKED_ZOOM) {
                    if (baseCameraScale <= 0f) {
                        baseCameraScale = currentCameraScale;
                    }
                    float scl = Mathf.clamp(currentCameraScale / baseCameraScale, 0.05f, 4.0f);
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
                Yr2Vars.worldToStage(worldPos.x, worldPos.y, tmpVec);
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
                pos.x = Mathf.clamp(pos.x, 30f - size.x, screenW - 30f);
                pos.y = Mathf.clamp(pos.y, 30f - size.y, screenH - size.y);
            }
            lastScreenW = screenW;
            lastScreenH = screenH;

            applyBoundsNow();
            onUpdate();
        });
    }

    public void applyBoundsNow() {
        if (attachedPaneMode) {
            setSize(size.x, size.y);
            validate();
            return;
        }
        if (showMainTable) {
            setPosition(pos.x, pos.y);
            setSize(size.x, size.y);
        } else {
            setPosition(pos.x, pos.y + size.y - 30f);
            setSize(size.x, 30f);
        }
        validate();
    }

    public void setAttachedPaneMode(boolean attached) {
        if (this.attachedPaneMode == attached) return;
        this.attachedPaneMode = attached;
        margin(attached ? 4f : 0f);
        mainTableBuild();
    }

    protected void onUpdate() {}

    @Override
    public void draw() {
        float s = isTransform() ? Math.max(scaleX, 0.0001f) : 1f;
        float ox = originX, oy = originY;
        float cx = x + ox * (1f - s);
        float cy = y + oy * (1f - s);
        if (clipBegin(cx, cy, width * s, height * s)) {
            super.draw();
            clipEnd();
        }
    }

    protected void onResized() {}

    /** 在【未锁定 -> 锁定并缩放(放大镜) -> 锁定(1:1)】三态之间循环切换。 */
    public void cyclePinState() {
        setPinState(switch (pinState) {
            case PIN_NONE -> PIN_LOCKED_ZOOM;
            case PIN_LOCKED_ZOOM -> PIN_LOCKED_1TO1;
            default -> PIN_NONE;
        });
    }

    public void setPinState(int newState) {
        int clamped = Mathf.clamp(newState, PIN_NONE, PIN_LOCKED_ZOOM);
        if (this.pinState == clamped) return;
        boolean wasPinned = this.pinned;
        this.pinState = clamped;
        this.pinned = (clamped != PIN_NONE);

        if (this.pinned) {
            if (!wasPinned) {
                syncWorldPos();
                bringToFocus();
            }
            if (clamped == PIN_LOCKED_ZOOM && Core.camera != null) {
                baseCameraScale = Core.graphics.getWidth() / Core.camera.width;
            } else {
                if (scaleX != 1f || scaleY != 1f) {
                    setScale(1f);
                    setTransform(false);
                }
                baseCameraScale = -1f;
            }
        } else {
            toFront();
            if (scaleX != 1f || scaleY != 1f) {
                setScale(1f);
                setTransform(false);
            }
            baseCameraScale = -1f;
        }
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
        Yr2Vars.stageToScreenPhys(tmpVec);
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
        if (blankPane != null && Core.scene != null) Core.scene.cancelTouchFocus(blankPane);
        return true;
    }

    private void mainTableBuild() {
        clear();
        top();
        if (!attachedPaneMode) {
            add(headTable).height(30).minWidth(0f).growX();
            if (!showMainTable) return;
            row();
        }
        stack(mainTable, gripTable).minWidth(0f).minHeight(0f).grow();
    }

    private void gripTableInit() {
        gripTable.clear();
        gripTable.bottom().right();
        gripTable.touchable = Touchable.childrenOnly;

        ImageButton grip = new ImageButton(Icon.resizeSmall, Styles.emptyi);
        grip.getStyle().imageUpColor = Yr2Vars.mutedColor;

        grip.addListener(new InputListener() {
            float startStageX, startStageY;
            float startW, startH;
            float startPosY;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startStageX = Yr2Vars.mouseX();
                startStageY = Yr2Vars.mouseY();
                startW = size.x;
                startH = size.y;
                startPosY = pos.y;
                grip.getStyle().imageUpColor = Yr2Vars.themeColor;
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float dx = Yr2Vars.mouseX() - startStageX;
                float dy = Yr2Vars.mouseY() - startStageY;

                float curScale = (!attachedPaneMode && isTransform() && scaleX > 0.01f) ? scaleX : 1f;
                float widthDelta = attachedPaneMode ? (dx * 2f) : (dx / curScale);
                float minW = attachedPaneMode ? Math.max(360f, minSize.x) : minSize.x;
                float minH = attachedPaneMode ? Math.max(200f, minSize.y) : minSize.y;

                float maxW = Yr2Vars.sceneW() - 12f;
                float maxH = Yr2Vars.sceneH() - 12f;
                float newW = Mathf.clamp(startW + widthDelta, minW, Math.max(minW, maxW));
                float newH = Mathf.clamp(startH - dy / curScale, minH, Math.max(minH, maxH));

                if (!pinned && !attachedPaneMode) {
                    pos.y = startPosY - (newH - startH);
                }

                size.set(newW, newH);
                if (!pinned && !attachedPaneMode) {
                    clampToScreen();
                }
                onResized();
                applyBoundsNow();
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                grip.getStyle().imageUpColor = Yr2Vars.mutedColor;
                applyBoundsNow();
            }
        });

        gripTable.add(grip).size(26f).pad(0f, 0f, 2f, 2f).bottom().right();
    }

    private void headTableInit(String text) {
        headTable.touchable = Touchable.enabled;

        Runnable toggleCollapse = () -> {
            showMainTable = !showMainTable;
            mainTableBuild();
            applyBoundsNow();
        };

        headTable.addListener(new InputListener() {
            long lastTapTime = 0L;
            boolean dragged = false;

            @Override
            public boolean touchDown(InputEvent event, float xDown, float yDown, int pointer, KeyCode button) {
                if (event.targetActor instanceof Button || (event.targetActor != null && event.targetActor.isDescendantOf(e -> e instanceof Button))) {
                    return false;
                }
                bias.set(xDown, yDown);
                dragged = false;
                bringToFocus();
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float xDragged, float yDragged, int pointer) {
                float dx = (xDragged - bias.x) * scaleX;
                float dy = (yDragged - bias.y) * scaleY;
                if (Math.abs(dx) > 2f || Math.abs(dy) > 2f) dragged = true;
                pos.add(dx, dy);
                if (pinned) syncWorldPos();
                applyBoundsNow();
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (!dragged) {
                    long now = Time.millis();
                    if (now - lastTapTime < 300L) {
                        lastTapTime = 0L;
                        toggleCollapse.run();
                        return;
                    }
                    lastTapTime = now;
                }
            }
        });

        Label title = new Label(text);
        title.setAlignment(Align.left);
        title.setEllipsis(true);
        headTable.add(title).pad(0, 10, 0, 10).height(30).minWidth(0).growX();

        if (pinnable) {
            // 三态锁按钮: 直接操作 pinButton.getStyle()，避免 Table.button 内部克隆 Style 导致图标不刷新！
            ImageButton pinButton = headTable.button(Icon.lockOpenSmall, Styles.emptyi, this::cyclePinState).size(30).right().get();
            pinButton.update(() -> {
                var s = pinButton.getStyle();
                s.imageUp = switch (pinState) {
                    case PIN_LOCKED_1TO1 -> Icon.zoomSmall;
                    case PIN_LOCKED_ZOOM -> Icon.lockSmall;
                    default -> Icon.lockOpenSmall;
                };
                s.imageUpColor = (pinState == PIN_NONE) ? Color.white : Yr2Vars.themeColor;
            });
        }

        ImageButton visButton = headTable.button(Icon.eyeSmall, Styles.emptyi, toggleCollapse).size(30).right().get();
        visButton.update(() -> visButton.getStyle().imageUp = showMainTable ? Icon.eyeSmall : Icon.eyeOffSmall);

        headTable.button(Icon.cancelSmall, Styles.emptyi, this::requestClose).size(30).right();
    }

    public void requestClose() {
        if (onClose != null) onClose.run();
        else removeFromScene();
    }

    public void addToScene() {
        if (hasParent() || Core.scene == null) return;
        Core.scene.root.addChild(this);
        visible(() -> Vars.state.isGame() && Vars.ui.hudfrag.shown && !hidden);
        bringToFocus();
    }

    public void removeFromScene() {
        if (parent != null) {
            remove();
        }
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
        pos.x = Mathf.clamp(pos.x, 30f - size.x, Yr2Vars.sceneW() - 30f);
        pos.y = Mathf.clamp(pos.y, 30f - size.y, Yr2Vars.sceneH() - size.y);
    }

    public static void configurePane(ScrollPane p) {
        p.setupFadeScrollBars(0.5f, 0.25f);
        p.setFadeScrollBars(true);
        p.setScrollingDisabled(true, false);
    }
}
