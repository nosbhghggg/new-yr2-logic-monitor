package yr2lm.ui.logic;

import arc.Core;
import arc.func.Cons;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import yr2lm.Yr2Vars;
import yr2lm.ui.window.Yrailiuxa2;

import java.util.ArrayList;

/**
 * 流水时序面板。
 */
public class TracePanel {
    public final Table tracePane;
    public final Table traceHeader;
    public final Table traceTable;
    public final ScrollPane traceScroll;

    private final TraceTimeline timeline;
    private final arc.func.Intc onScrollToLine;
    private Cons<TraceTimeline.Record> onSelectStep;
    private Runnable onClearHistory;

    static final float HEAD_PAD_L = 4f;
    static final float HEAD_BTN = 28f;

    @SuppressWarnings("this-escape")
    public TracePanel(TraceTimeline timeline, arc.func.Intc onScrollToLine) {
        this.timeline = timeline;
        this.onScrollToLine = onScrollToLine;
        traceTable = new Table();
        traceTable.top().left();
        traceScroll = new ScrollPane(traceTable);
        Yrailiuxa2.configurePane(traceScroll);
        traceScroll.update(() -> Yr2Vars.bindScrollFocus(traceScroll));
        traceHeader = new Table();
        traceHeader.left();
        tracePane = new Table();
        tracePane.top().left();
        buildHeader();
        rebuild();
    }

    public void setListeners(Cons<TraceTimeline.Record> onSelectStep, Runnable onClearHistory) {
        this.onSelectStep = onSelectStep;
        this.onClearHistory = onClearHistory;
    }

    private void buildHeader() {
        traceHeader.clear();
        traceHeader.left();
        traceHeader.add(new Label("[#" + Yr2Vars.mutedHex + "]#[]", Styles.outlineLabel)).left().minWidth(0f).growX().padLeft(HEAD_PAD_L);
        traceHeader.button(Icon.copySmall, Styles.emptyi, () -> {
            StringBuilder sb = new StringBuilder("# yr2lm trace\n");
            ArrayList<TraceTimeline.Record> history = timeline.getHistory();
            for (int i = history.size() - 1; i >= 0; i--) {
                sb.append(history.get(i).toString()).append("\n");
            }
            Core.app.setClipboardText(sb.toString());
        }).size(HEAD_BTN);

        traceHeader.button(Icon.trashSmall, Styles.emptyi, () -> {
            if (onClearHistory != null) onClearHistory.run();
        }).size(HEAD_BTN);

        tracePane.clear();
        tracePane.top().left();
        tracePane.add(traceHeader).height(30f).minWidth(0f).growX().padBottom(2f);
        tracePane.row();
        tracePane.add(traceScroll).minWidth(0f).minHeight(0f).grow();
    }

    public void rebuild() {
        traceTable.clearChildren();
        traceTable.top().left();
        ArrayList<TraceTimeline.Record> history = timeline.getHistory();
        if (history.isEmpty()) {
            traceTable.add(new Label("[#" + Yr2Vars.mutedHex + "]---", Styles.outlineLabel)).padTop(20f).center();
            return;
        }
        TraceTimeline.Record selected = timeline.getSelected();
        int highlightedLine = timeline.getHighlightedLine();

        for (int i = history.size() - 1; i >= 0; i--) {
            TraceTimeline.Record rec = history.get(i);
            if (rec.isCollapsed) {
                traceTable.table(row -> {
                    row.center().margin(1f, 2f, 1f, 2f);
                    Label lbl = new Label("[#" + Yr2Vars.mutedHex + "]L" + rec.fromLine + " -> L" + rec.toLine + " (" + rec.collapsedCount + ")");
                    lbl.setEllipsis(true);
                    row.add(lbl).minWidth(0f).growX().center();
                    row.clicked(() -> {
                        if (onScrollToLine != null) onScrollToLine.get(rec.fromLine);
                    });
                }).minHeight(20f).minWidth(0f).growX();
                traceTable.row();
                continue;
            }

            boolean isCurrent = (selected == rec || highlightedLine == rec.fromLine);
            traceTable.table(card -> {
                card.left().margin(2f, 4f, 2f, 4f);

                card.table(headRow -> {
                    headRow.left();
                    String prefix = (isCurrent ? "[#" + Yr2Vars.themeHex + "]> []" : "")
                            + "[#" + Yr2Vars.mutedHex + "]#" + rec.step + " [L" + rec.fromLine + "] ";
                    headRow.add(new Label(prefix)).left();

                    if (rec.isJump) {
                        headRow.add(new Label("[#" + Yr2Vars.jumpHex + "]-> L" + rec.toLine)).left().padRight(4f);
                    }
                }).minWidth(0f).growX().left();
                card.row();

                if (rec.diffList != null && !rec.diffList.isEmpty()) {
                    for (String d : rec.diffList) {
                        card.table(diffRow -> {
                            diffRow.left().marginLeft(10f);
                            Label diffLbl = new Label("[#" + Yr2Vars.dataChangeHex + "]" + d);
                            diffLbl.setWrap(true);
                            diffRow.add(diffLbl).minWidth(0f).growX().left();
                        }).minWidth(0f).growX().left();
                        card.row();
                    }
                }

                card.clicked(() -> {
                    if (onSelectStep != null) onSelectStep.get(rec);
                });
            }).minHeight(24f).minWidth(0f).growX().padBottom(2f);
            traceTable.row();
        }
    }
}
