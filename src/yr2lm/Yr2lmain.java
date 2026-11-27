package yr2lm;

import arc.Events;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import yr2lm.ui.inject.ConfigInjector;
import yr2lm.util.LogicHighlight;

/**
 * 模组主入口。
 */
public class Yr2lmain extends Mod {
    public Yr2lmain() {
        Events.on(EventType.ContentInitEvent.class, e -> LogicHighlight.refresh());
        Events.on(EventType.ClientLoadEvent.class, e -> {
            LogicHighlight.refresh();
            Yr2Vars.ensureCombination();
            ConfigInjector.init();
            Time.runTask(10f, () -> Yr2Vars.ensureCombination().addToScene());
        });
        Events.on(EventType.WorldLoadEvent.class, e -> {
            if (Yr2Vars.combination != null) {
                Yr2Vars.combination.clearMonitor();
            }
        });
    }
}
