package com.hyproj.blitzlink.mods;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hyproj.blitzlink.config.BlitzConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Decides which mods a client must have: content in synced built-in registries means the client needs the mod too. */
public final class ModSides {
    private ModSides() {}

    /** Registries whose entries the client must know by id (blocks, items, entities, menus, …). */
    private static List<Registry<?>> synced() {
        return List.of(
                BuiltInRegistries.BLOCK,
                BuiltInRegistries.ITEM,
                BuiltInRegistries.ENTITY_TYPE,
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                BuiltInRegistries.MENU,
                BuiltInRegistries.FLUID,
                BuiltInRegistries.PARTICLE_TYPE,
                BuiltInRegistries.MOB_EFFECT,
                BuiltInRegistries.DATA_COMPONENT_TYPE,
                BuiltInRegistries.SOUND_EVENT,
                BuiltInRegistries.RECIPE_SERIALIZER,
                BuiltInRegistries.ATTRIBUTE,
                BuiltInRegistries.POTION);
    }

    /** Non-minecraft namespaces that own at least one entry in a synced registry. */
    public static Set<String> contentNamespaces() {
        Set<String> out = new HashSet<>();
        for (Registry<?> r : synced()) {
            for (Identifier id : r.keySet()) {
                if (!"minecraft".equals(id.getNamespace())) out.add(id.getNamespace());
            }
        }
        return out;
    }

    /** Config overrides win over what the loader/registries say. */
    public static ModEntry.Side override(String modId, ModEntry.Side side) {
        BlitzConfig cfg = BlitzConfig.get();
        if (cfg.serverOnlyMods.contains(modId)) return ModEntry.Side.SERVER;
        if (cfg.requiredClientMods.contains(modId)) return ModEntry.Side.REQUIRED;
        return side;
    }
}
