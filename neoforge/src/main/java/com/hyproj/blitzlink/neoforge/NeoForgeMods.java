package com.hyproj.blitzlink.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hyproj.blitzlink.mods.ModEntry;
import com.hyproj.blitzlink.mods.ModSides;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * NeoForge has no per-mod side flag, so a mod counts as REQUIRED on the client when its namespace owns entries in a
 * synced registry or registered network payloads (ModSides.contentNamespaces()); everything else is OPTIONAL.
 * Config overrides (requiredClientMods / serverOnlyMods) win.
 */
final class NeoForgeMods {
    private static final Set<String> BUILTIN = Set.of("minecraft", "neoforge", "blitzlink");

    private NeoForgeMods() {}

    static List<ModEntry> collect() {
        Set<String> content = ModSides.contentNamespaces();
        List<ModEntry> out = new ArrayList<>();
        for (IModInfo m : ModList.get().getMods()) {
            String id = m.getModId();
            ModEntry.Side side;
            if (BUILTIN.contains(id)) {
                side = ModEntry.Side.BUILTIN;
            } else {
                side = content.contains(m.getNamespace()) || content.contains(id) ? ModEntry.Side.REQUIRED : ModEntry.Side.OPTIONAL;
            }
            out.add(new ModEntry(id, m.getDisplayName(), m.getVersion().toString(), ModSides.override(id, side)));
        }
        return out;
    }
}
