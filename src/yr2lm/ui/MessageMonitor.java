package yr2lm.ui;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.MessageBlock;

import java.util.ArrayList;

/**
 * 信息板日志监视窗口。
 * <p>
 * 支持实时消息监控、暂停、手动刷新与清空。
 * 内存与性能保护: 限制最大日志条数(128条)，避免高频逻辑打印导致内存无限膨胀。
 */
public class MessageMonitor extends Monitor {
    private static final int MAX_LOGS = 128;

    private final MessageBlock.MessageBuild messageBuild;
    private int mesHash;
    private boolean pause = false;
    private final Table mesTools;

    private class MesCell extends Table {
        public String message;

        public MesCell(String message) {
            super();
            this.message = message;
            table(t -> {
                t.labelWrap(message).grow().pad(0, 10, 0, 5);
                t.button(Icon.copySmall, Styles.emptyi, () -> Core.app.setClipboardText(message)).size(35).right();
                t.button(Icon.cancelSmall, Styles.emptyi, () -> {
                    mesCells.remove(this);
                    init();
                }).size(35).right();
                t.labelWrap(String.valueOf((int)Time.time % 10000)).size(60, 35);
            }).minHeight(35).growX();
        }
    }

    private final ArrayList<MesCell> mesCells;

    @SuppressWarnings("this-escape")
    public MessageMonitor(String text, MessageBlock.MessageBuild messageBuildInit, Vec2 pos) {
        super(text, messageBuildInit, pos);
        messageBuild = messageBuildInit;
        mesHash = messageBuild.message.toString().hashCode();
        mesTools = new Table();
        mesCells = new ArrayList<>();
        mesToolsBuild();
        init();
        size.set(400f, 280f);
        minSize.set(300f, 200f);
    }

    @Override
    public void init() {
        if (mesCells.isEmpty()) mesCells.add(new MesCell(messageBuild.message.toString()));
        monitorTable.clear();
        monitorTable.add(mesTools).growX();
        monitorTable.row();
        monitorTable.pane(p -> {
            p.top();
            mesCells.forEach(mesCell -> {
                p.add(mesCell).growX();
                p.row();
            });
        }).grow().update(Yrailiuxa2::bindScrollFocus).with(Yrailiuxa2::configurePane);
    }

    private void mesToolsBuild() {
        mesTools.clear();
        mesTools.table(t -> {
            ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.emptyi);
            ImageButton drawButton = t.button(Icon.pause, Styles.emptyi, () -> {}).grow().get();
            drawButton.clicked(() -> {
                pause = !pause;
                style.imageUp = pause ? Icon.play : Icon.pause;
                drawButton.setStyle(style);
            });
            t.button(Icon.refresh, Styles.emptyi, this::init).grow();
            t.button(Icon.trash, Styles.emptyi, () -> {
                mesCells.clear();
                init();
            }).grow();
        }).height(40).growX().update(t -> {
            if (pause) return;
            String message = messageBuild.message.toString();
            if (message.hashCode() != mesHash) {
                if (mesCells.size() >= MAX_LOGS) {
                    mesCells.remove(mesCells.size() - 1);
                }
                mesCells.add(0, new MesCell(message));
                mesHash = message.hashCode();
                init();
            }
        });
    }

    @Override
    public MessageBlock.MessageBuild getBuilding() {
        return messageBuild;
    }
}
