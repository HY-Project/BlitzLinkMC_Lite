package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless Linux servers: webrtc-java's Linux library links against X11, GLib/GIO, GBM and DRM for screen capture and
 * PipeWire, which a BlitzLink tunnel never touches — but the dynamic linker still refuses to load it when a server image
 * lacks those libraries ({@code libX11.so.6: cannot open shared object file}).
 *
 * <p>The library is linked for lazy binding (no BIND_NOW) and imports only functions from them, so an empty stand-in that
 * carries the right SONAME is enough: once such a stand-in is loaded, glibc treats the dependency as satisfied, and the
 * capture functions that would need the real library are never called. For every listed library that this machine does
 * not have, we write a minimal ELF shared object (no code, no symbols, just its SONAME) and load it before libwebrtc.
 * Libraries that exist are left alone. {@code -Dblitzlink.lite.noStubs=true} turns this off.
 */
final class HeadlessLinux {
    /** NEEDED entries of libwebrtc-java-linux-*.so beyond glibc (webrtc-java 1.0.0). */
    static final List<String> CAPTURE_LIBS = List.of(
            "libX11.so.6", "libXext.so.6", "libXfixes.so.3", "libXdamage.so.1", "libXtst.so.6", "libXrandr.so.2", "libXcomposite.so.1",
            "libglib-2.0.so.0", "libgobject-2.0.so.0", "libgio-2.0.so.0", "libdrm.so.2", "libgbm.so.1");

    private static final List<String> LIB_DIRS = List.of(
            "/lib", "/lib64", "/usr/lib", "/usr/lib64", "/usr/local/lib",
            "/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu", "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
            "/usr/lib/x86_64-linux-musl", "/usr/lib/aarch64-linux-musl");

    private static boolean done;

    private HeadlessLinux() {}

    /** Loads stand-ins for the missing capture libraries (Linux x64/arm64 only; elsewhere a no-op). */
    static synchronized void prepare(Path nativeDir) {
        if (done) return;
        done = true;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") || Boolean.getBoolean("blitzlink.lite.noStubs")) return;
        int machine = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> 62; // EM_X86_64
            case "aarch64", "arm64" -> 183; // EM_AARCH64
            default -> 0;
        };
        if (machine == 0 || nativeDir == null) return;
        List<String> missing = new ArrayList<>();
        for (String lib : CAPTURE_LIBS) {
            if (!present(lib)) missing.add(lib);
        }
        if (missing.isEmpty()) return;
        Path dir = nativeDir.resolve("linux-stubs-" + machine);
        try {
            Files.createDirectories(dir);
            for (String lib : missing) {
                Path p = dir.resolve(lib);
                byte[] elf = stub(lib, machine);
                if (!Files.exists(p) || Files.size(p) != elf.length) Files.write(p, elf);
                System.load(p.toAbsolutePath().toString());
            }
        } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
            // libwebrtc's own load then reports what is missing
        }
    }

    private static boolean present(String lib) {
        List<String> dirs = new ArrayList<>(LIB_DIRS);
        String ld = System.getenv("LD_LIBRARY_PATH");
        if (ld != null) dirs.addAll(0, List.of(ld.split(":")));
        for (String d : dirs) {
            if (!d.isEmpty() && Files.exists(Path.of(d, lib))) return true;
        }
        return false;
    }

    /**
     * A 64-bit little-endian ELF shared object with nothing but a SONAME: one PT_LOAD over the whole file, PT_DYNAMIC
     * (HASH/STRTAB/SYMTAB with the null symbol only, SONAME) and PT_GNU_STACK without execute permission.
     */
    static byte[] stub(String soname, int machine) {
        byte[] name = soname.getBytes(StandardCharsets.US_ASCII);
        int ehdr = 64;
        int phnum = 3;
        int strtab = ehdr + 56 * phnum;
        int strsz = 1 + name.length + 1;
        int symtab = align(strtab + strsz, 8);
        int hash = symtab + 24;
        int dynamic = hash + 16;
        int dynCount = 7;
        int size = dynamic + 16 * dynCount;

        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        // e_ident: magic, 64-bit, little-endian, version 1, System V ABI
        b.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        b.putShort((short) 3); // ET_DYN
        b.putShort((short) machine);
        b.putInt(1); // EV_CURRENT
        b.putLong(0); // e_entry
        b.putLong(ehdr); // e_phoff
        b.putLong(0); // e_shoff
        b.putInt(0); // e_flags
        b.putShort((short) ehdr);
        b.putShort((short) 56); // e_phentsize
        b.putShort((short) phnum);
        b.putShort((short) 64); // e_shentsize
        b.putShort((short) 0); // e_shnum
        b.putShort((short) 0); // e_shstrndx
        // PT_LOAD (R+W) over everything
        phdr(b, 1, 6, 0, size, 0x1000);
        // PT_DYNAMIC
        phdr(b, 2, 6, dynamic, 16 * dynCount, 8);
        // PT_GNU_STACK, not executable (newer glibc refuses to dlopen objects that ask for an executable stack)
        phdr(b, 0x6474e551, 6, 0, 0, 16);
        // .dynstr: "\0" soname "\0"
        b.position(strtab);
        b.put((byte) 0).put(name).put((byte) 0);
        // .dynsym: the null symbol (already zero); .hash: nbucket = nchain = 1, bucket[0] = chain[0] = 0
        b.position(hash);
        b.putInt(1).putInt(1).putInt(0).putInt(0);
        // .dynamic
        b.position(dynamic);
        dyn(b, 4, hash); // DT_HASH
        dyn(b, 5, strtab); // DT_STRTAB
        dyn(b, 6, symtab); // DT_SYMTAB
        dyn(b, 10, strsz); // DT_STRSZ
        dyn(b, 11, 24); // DT_SYMENT
        dyn(b, 14, 1); // DT_SONAME (offset in .dynstr)
        dyn(b, 0, 0); // DT_NULL
        return b.array();
    }

    private static void phdr(ByteBuffer b, int type, int flags, long offset, long size, long align) {
        b.putInt(type).putInt(flags).putLong(offset).putLong(offset).putLong(offset).putLong(size).putLong(size).putLong(align);
    }

    private static void dyn(ByteBuffer b, long tag, long val) {
        b.putLong(tag).putLong(val);
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }
}
