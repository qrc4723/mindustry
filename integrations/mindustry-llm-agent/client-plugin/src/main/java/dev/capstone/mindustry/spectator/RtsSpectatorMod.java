package dev.capstone.mindustry.spectator;

import arc.Events;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Decal;
import mindustry.gen.Groups;
import mindustry.mod.Mod;

/** Client-only visual cleanup for the local RTS observer. */
public final class RtsSpectatorMod extends Mod {
    private final Seq<Decal> pendingRemoval = new Seq<>();

    @Override
    public void init() {
        Events.run(Trigger.update, () -> {
            if (Vars.headless || Vars.state == null || !Vars.state.isGame() || !Vars.state.rules.pvp) return;
            pendingRemoval.clear();
            Groups.all.each(entity -> {
                if (entity instanceof Decal decal) pendingRemoval.add(decal);
            });
            pendingRemoval.each(Decal::remove);
        });
    }
}
