package com.hyproj.blitzlink.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyproj.blitzlink.mods.ModEntry;
import net.minecraft.util.FileUtil;

/**
 * 「서버 ZIP 가져오기」: a zipped server folder (or a zipped world) becomes a singleplayer save that BlitzLink then
 * opens as a server. Finds the shallowest {@code level.dat}, copies that world (guarding against zip-slip), merges
 * Bukkit/Paper split dimensions ({@code <world>_nether/DIM-1}, {@code <world>_the_end/DIM1}) back into the world, reads
 * {@code server.properties} and the mods inside {@code mods/*.jar} so the player can see which ones the client lacks
 * (an integrated server runs with the client's own mods).
 */
public final class ServerZipImport {
    /** What a zip holds, before anything is written. */
    public record Analysis(Path zip, String worldRoot, String worldName, Properties properties, List<ModEntry> mods, long bytes) {
        public String motd() {
            return properties.getProperty("motd", "");
        }
    }

    private static final long MAX_TOTAL = 16L * 1024 * 1024 * 1024; // 16 GB uncompressed
    private static final int MAX_ENTRIES = 2_000_000;

    private ServerZipImport() {}

    public static Analysis analyze(Path zip) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            String best = null;
            long total = 0;
            int count = 0;
            Properties props = new Properties();
            String propsDir = null;
            ZipEntry propsEntry = null;
            List<ModEntry> mods = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            List<ZipEntry> modJars = new ArrayList<>();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("tooManyEntries");
                String name = clean(e.getName());
                if (name == null) continue;
                total += Math.max(0, e.getSize());
                if (name.endsWith("/level.dat") || name.equals("level.dat")) {
                    String root = name.substring(0, name.length() - "level.dat".length());
                    if (best == null || depth(root) < depth(best)) best = root;
                }
                if ((name.equals("server.properties") || name.endsWith("/server.properties")) && (propsDir == null || depth(name) < depth(propsDir))) {
                    propsDir = name;
                    propsEntry = e;
                }
                if (name.matches("(.*/)?mods/[^/]+\\.jar") && !e.isDirectory()) modJars.add(e);
            }
            if (best == null) throw new IOException("noWorld");
            if (total > MAX_TOTAL) throw new IOException("tooLarge");
            if (propsEntry != null) {
                try (InputStream in = z.getInputStream(propsEntry)) {
                    props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
            for (ZipEntry jar : modJars) {
                try (InputStream in = z.getInputStream(jar)) {
                    ModEntry m = readModJar(in.readAllBytes(), jar.getName());
                    if (m != null) mods.add(m);
                }
            }
            String dirName = best.isEmpty() ? stripExt(zip.getFileName().toString()) : lastSegment(best);
            String worldName = props.getProperty("level-name", "").isBlank() ? dirName : props.getProperty("level-name");
            if (!props.getProperty("motd", "").isBlank()) worldName = stripFormatting(props.getProperty("motd")).lines().findFirst().orElse(worldName);
            return new Analysis(zip, best, worldName, props, mods, total);
        }
    }

    /** Copies the world into {@code savesDir/<free name>} and returns the new level id. */
    public static String install(Analysis a, Path savesDir) throws IOException {
        String base = a.worldName().replaceAll("[^\\p{L}\\p{N} _-]", "_").strip();
        if (base.isEmpty()) base = "Imported Server";
        String levelId = FileUtil.findAvailableName(savesDir, base, "");
        Path target = savesDir.resolve(levelId).normalize();
        Files.createDirectories(target);
        String root = a.worldRoot();
        String rootName = root.isEmpty() ? "" : lastSegment(root);
        String parent = root.isEmpty() ? "" : root.substring(0, root.length() - rootName.length() - 1);
        // Bukkit/Paper keep the Nether and End in sibling folders: <world>_nether/DIM-1, <world>_the_end/DIM1.
        Map<String, String> extra = new HashMap<>();
        if (!rootName.isEmpty()) {
            extra.put(parent + rootName + "_nether/DIM-1/", "DIM-1/");
            extra.put(parent + rootName + "_the_end/DIM1/", "DIM1/");
            extra.put(parent + rootName + "_nether/dimensions/", "dimensions/");
            extra.put(parent + rootName + "_the_end/dimensions/", "dimensions/");
        }
        try (ZipFile z = new ZipFile(a.zip().toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = clean(e.getName());
                if (name == null) continue;
                String rel = null;
                if (name.startsWith(root)) {
                    rel = name.substring(root.length());
                } else {
                    for (Map.Entry<String, String> x : extra.entrySet()) {
                        if (name.startsWith(x.getKey())) {
                            rel = x.getValue() + name.substring(x.getKey().length());
                            break;
                        }
                    }
                }
                if (rel == null || rel.isEmpty() || rel.equals("session.lock") || rel.endsWith("/session.lock")) continue;
                Path out = target.resolve(rel).normalize();
                if (!out.startsWith(target)) throw new IOException("badEntry");
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return levelId;
    }

    // ---- mod jars inside the zip ----

    private static final Pattern TOML_MOD_ID = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_NAME = Pattern.compile("displayName\\s*=\\s*\"([^\"]+)\"");

    /** Reads fabric.mod.json or META-INF/neoforge.mods.toml from a nested jar; jar file name when neither is there. */
    static ModEntry readModJar(byte[] jar, String entryName) throws IOException {
        String fabric = null, toml = null, manifestVersion = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("fabric.mod.json")) fabric = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
                else if (n.equals("META-INF/neoforge.mods.toml") || n.equals("META-INF/mods.toml")) toml = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
                else if (n.equals("META-INF/MANIFEST.MF")) {
                    String mf = new String(in.readNBytes(1 << 16), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("Implementation-Version:\\s*(\\S+)").matcher(mf);
                    if (m.find()) manifestVersion = m.group(1);
                }
            }
        }
        if (fabric != null) {
            JsonObject o = JsonParser.parseString(fabric).getAsJsonObject();
            String id = o.has("id") ? o.get("id").getAsString() : stripExt(lastSegment(entryName));
            String env = o.has("environment") ? o.get("environment").getAsString() : "*";
            ModEntry.Side side = "server".equals(env) ? ModEntry.Side.SERVER : "client".equals(env) ? ModEntry.Side.CLIENT : ModEntry.Side.REQUIRED;
            return new ModEntry(id, o.has("name") ? o.get("name").getAsString() : id, o.has("version") ? o.get("version").getAsString() : "?", side);
        }
        if (toml != null) {
            Matcher id = TOML_MOD_ID.matcher(toml);
            if (!id.find()) return null;
            Matcher v = TOML_VERSION.matcher(toml);
            String version = v.find() ? v.group(1) : "?";
            if (version.contains("${file.jarVersion}") && manifestVersion != null) version = manifestVersion;
            Matcher name = TOML_NAME.matcher(toml);
            return new ModEntry(id.group(1), name.find() ? name.group(1) : id.group(1), version, ModEntry.Side.REQUIRED);
        }
        String file = stripExt(lastSegment(entryName));
        return new ModEntry(file.toLowerCase(Locale.ROOT), file, "?", ModEntry.Side.OPTIONAL);
    }

    // ---- helpers ----

    /** Normalised forward-slash name, or null for anything that could escape the target folder. */
    private static String clean(String raw) {
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/") || n.contains("../") || n.equals("..") || n.contains(":")) return null;
        if (n.startsWith("__MACOSX/")) return null;
        return n;
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static String lastSegment(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String stripFormatting(String s) {
        return s.replaceAll("§.", "").replace("\\n", "\n");
    }
}
