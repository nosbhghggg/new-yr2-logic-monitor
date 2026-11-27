package yr2lm.ui.window;

import arc.func.Cons;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import yr2lm.Yr2Vars;

/**
 * 分栏自由拖拽分割线: 支持左右无级拖拽调整分栏宽度，双击快速重置，松手触发持久化回调。
 */
public class Splitter extends Table {
    private boolean isDown = false;
    private boolean moved = false;

    @SuppressWarnings("this-escape")
    public Splitter(Cons<Float> onDrag, Runnable onReset, Runnable onRelease) {
        touchable = Touchable.enabled;
        addListener(new InputListener() {
            float startStageX;
            long lastClickTime = 0;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                startStageX = Yr2Vars.mouseX();
                isDown = true;
                moved = false;
                long now = Time.millis();
                if (now - lastClickTime < 300 && onReset != null) {
                    onReset.run();
                    lastClickTime = 0;
                    event.stop();
                    return true;
                }
                lastClickTime = now;
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                float curStageX = Yr2Vars.mouseX();
                float dx = curStageX - startStageX;
                startStageX = curStageX;
                if (dx != 0f) moved = true;
                onDrag.get(dx);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                isDown = false;
                if (moved && onRelease != null) onRelease.run();
            }
        });
    }

    @Override
    public void draw() {
        super.draw();
        boolean hover = Yr2Vars.isHovered(this);
        if (isDown || hover) {
            Draw.color(Yr2Vars.themeColor, isDown ? 0.22f : 0.10f);
            Fill.rect(x + width / 2f, y + height / 2f, width, height);
        }
        Draw.color(isDown || hover ? Yr2Vars.themeColor : Yr2Vars.mutedColor, isDown ? 0.90f : (hover ? 0.60f : 0.20f));
        Fill.rect(x + width / 2f, y + height / 2f, 2f, height);
        Draw.reset();
    }
}
