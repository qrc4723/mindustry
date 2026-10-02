package dev.capstone.mindustry.spectator;

import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Decal;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.mod.Mod;

/** Client-only visual cleanup for the local RTS observer. */
public final class RtsSpectatorMod extends Mod {
    private final Seq<Decal> pendingRemoval = new Seq<>();
    private final Seq<Unit> pendingDeadUnitRemoval = new Seq<>();

    @Override
    public void init() {
        Log.info("[RTS spectator] client-only wreck cleanup active.");
        Events.run(Trigger.update, () -> {
            if (Vars.headless || Vars.state == null || !Vars.state.isGame() || !Vars.state.rules.pvp) return;

            // Flying units remain as falling wreck entities until the authoritative
            // server destroys them. Removing them from this observer client only
            // keeps the recording readable without changing combat simulation.
            pendingDeadUnitRemoval.clear();
            Groups.unit.each(unit -> {
                if (unit.dead || unit.health <= 0f) pendingDeadUnitRemoval.add(unit);
            });
            pendingDeadUnitRemoval.each(Unit::remove);

            // Decals are guaranteed to be in the draw group. This removes both the
            // unit wreck sprites and their scorch marks on the following frame.
            pendingRemoval.clear();
            Groups.draw.each(entity -> {
                if (entity instanceof Decal decal) pendingRemoval.add(decal);
            });
            pendingRemoval.each(Decal::remove);
        });
    }
}
