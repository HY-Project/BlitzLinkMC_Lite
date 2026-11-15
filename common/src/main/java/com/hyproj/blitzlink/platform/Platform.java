package com.hyproj.blitzlink.platform;

import java.nio.file.Path;
import java.util.List;

import com.hyproj.blitzlink.mods.ModEntry;

/**
 * What common code needs from the mod loader. One universal jar carries both implementations, so the loader is
 * detected by class presence and only that implementation is loaded (the other one would fail to link).
 */
public interface Platform {
    /** "neoforge" or "fabric" — the same strings the relay listing uses. */
    String loaderName();

    String loaderVersion();

    /** The version string of a loaded mod, or "?" when it is not loaded. */
    String modVersion(String modId);

    /** Every loaded mod, with the side information this loader knows (see ModEntry.Side). */
    List<ModEntry> mods();

    Path gameDir();

    Path configDir();

    boolean isDedicatedServer();

    static Platform get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final Platform INSTANCE = detect();

        private static Platform detect() {
            String impl = present("net.neoforged.fml.ModList") ? "com.hyproj.blitzlink.neoforge.NeoForgePlatform"
                    : present("net.fabricmc.loader.api.FabricLoader") ? "com.hyproj.blitzlink.fabric.FabricPlatform"
                    : null;
            if (impl == null) throw new IllegalStateException("BlitzLink: running on neither NeoForge nor Fabric");
            try {
                return (Platform) Class.forName(impl, true, Platform.class.getClassLoader()).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("BlitzLink: could not create " + impl, e);
            }
        }

        private static boolean present(String cls) {
            try {
                Class.forName(cls, false, Platform.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }

        private Holder() {}
    }
}
