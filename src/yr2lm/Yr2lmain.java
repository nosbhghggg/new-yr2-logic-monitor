package yr2lm;

import arc.Events;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Mod;
import mindustry.world.blocks.logic.MemoryBlock;
import yr2lm.ui.ConfigInjector;
import yr2lm.util.LogicHighlight;

public class Yr2lmain extends Mod {
    public Yr2lmain() {
        ConfigInjector.init();

        // 统一所有方块的交互体验: 将原版内存块标记为可配置，使其与逻辑块、信息板完全一致地弹出原生配置栏与齿轮
        Events.on(EventType.ContentInitEvent.class, e -> {
            enableMemoryConfig();
            LogicHighlight.refresh();
        });
        Events.on(EventType.ClientLoadEvent.class, e -> {
            enableMemoryConfig();
            LogicHighlight.refresh();
            Time.runTask(10f, Yr2Vars.combination::addToScene);
        });
        Events.on(EventType.WorldLoadEvent.class, e -> Yr2Vars.combination.clearMonitor());
    }

    private static void enableMemoryConfig() {
        if (Vars.content == null) return;
        Vars.content.blocks().each(b -> {
            if (b instanceof MemoryBlock mb) {
                mb.configurable = true;
            }
        });
    }
}
