package yr2lm.ui.monitor;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.ui.ImageButton;
import arc.util.Align;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.MessageBlock;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Monitor;
import yr2lm.ui.window.Yrailiuxa2;

import java.util.ArrayList;

/**
 * 信息板日志监视窗口。
 */
public class MessageMonitor extends Monitor {
    private static final int MAX_LOGS = 128;

    private final MessageBlock.MessageBuild messageBuild;
    private String lastMessage;
    private boolean pause = false;
    private final Table mesTools;
    private final Table listTable;
    private final ScrollPane scrollPane;
    private final ArrayList<MesCell> mesCells = new ArrayList<>();

    private class MesCell extends Table {
        public final String message;
        public final String timestamp;

        public MesCell(String message, String timestamp) {
            super();
            this.message = message;
            this.timestamp = timestamp;
            table(t -> {
                t.labelWrap(message).minWidth(0f).grow().pad(0, 10, 0, 5);
                t.button(Icon.copySmall, Styles.emptyi, () -> Core.app.setClipboardText(message)).size(35).right();
                t.button(Icon.cancelSmall, Styles.emptyi, () -> {
                    mesCells.remove(this);
                    rebuildListRows();
                }).size(35).right();
                t.label(() -> "[#" + Yr2Vars.mutedHex + "]" + timestamp).width(84f).growY().padLeft(4).padRight(8).get().setAlignment(Align.right);
            }).minHeight(35).minWidth(0f).growX();
        }
    }

    private static String formatGameTimestamp() {
        float totalSec = Math.max(0f, Time.time / 60f);
        int mins = (int) (totalSec / 60f) % 100;
        float secs = totalSec % 60f;
        return String.format("%02d:%04.1f", mins, secs);
    }

    @SuppressWarnings("this-escape")
    public MessageMonitor(String text, MessageBlock.MessageBuild messageBuildInit, Vec2 pos, float initW, float initH) {
        super(text, messageBuildInit, pos);
        messageBuild = messageBuildInit;
        size.set(initW, initH);
        minSize.set(240f, 180f);

        lastMessage = messageBuild.message.toString();
        mesTools = new Table();
        listTable = new Table();
        listTable.top();
        scrollPane = new ScrollPane(listTable);
        Yrailiuxa2.configurePane(scrollPane);
        scrollPane.update(() -> Yr2Vars.bindScrollFocus(scrollPane));

        mesCells.add(new MesCell(lastMessage, formatGameTimestamp()));
        mesToolsBuild();
        init();
    }

    @Override
    public void init() {
        if (mesCells.isEmpty()) {
            lastMessage = messageBuild.message.toString();
            mesCells.add(new MesCell(lastMessage, formatGameTimestamp()));
        }
        rebuildListRows();
        monitorTable.clear();
        monitorTable.add(mesTools).minWidth(0f).growX();
        monitorTable.row();
        monitorTable.add(scrollPane).minWidth(0f).minHeight(0f).grow();
    }

    private void rebuildListRows() {
        listTable.clearChildren();
        listTable.top();
        for (MesCell cell : mesCells) {
            listTable.add(cell).minWidth(0f).growX();
            listTable.row();
        }
    }

    private void mesToolsBuild() {
        mesTools.clear();
        mesTools.table(t -> {
            ImageButton drawButton = t.button(Icon.pause, Styles.emptyi, () -> pause = !pause).minWidth(0f).grow().get();
            drawButton.update(() -> drawButton.getStyle().imageUp = pause ? Icon.play : Icon.pause);
            t.button(Icon.refresh, Styles.emptyi, this::init).minWidth(0f).grow();
            t.button(Icon.trash, Styles.emptyi, () -> {
                mesCells.clear();
                init();
            }).minWidth(0f).grow();
        }).height(40).minWidth(0f).growX().update(t -> {
            if (pause) return;
            if (!lastMessage.contentEquals(messageBuild.message)) {
                String message = messageBuild.message.toString();
                lastMessage = message;
                if (mesCells.size() >= MAX_LOGS) {
                    mesCells.remove(mesCells.size() - 1);
                }
                mesCells.add(0, new MesCell(message, formatGameTimestamp()));
                rebuildListRows();
            }
        });
    }

    @Override
    public MessageBlock.MessageBuild getBuilding() {
        return messageBuild;
    }
}
