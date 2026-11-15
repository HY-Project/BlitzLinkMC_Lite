package com.hyproj.blitzlink.client.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.client.BlitzLinkClient;
import com.hyproj.blitzlink.client.ServerZipImport;
import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzList;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import com.hyproj.blitzlink.mods.ModCompare;
import com.hyproj.blitzlink.mods.ServerInfo;
import com.hyproj.blitzlink.platform.Platform;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 「서버 ZIP 가져오기」: drop a server (or world) ZIP on this screen, or put it in {@code <game>/blitzlink-import} and
 * press 새로고침. Shows what was found — world, server.properties MOTD, and the ZIP's mods checked against this client
 * (an integrated server only has the client's mods: missing ones are red, their blocks and items would be lost).
 */
public final class ImportScreen extends BlitzScreen {
    private enum Step { PICK, ANALYZING, REVIEW, INSTALLING }

    private Step step = Step.PICK;
    private List<Path> zips = List.of();
    private ServerZipImport.Analysis analysis;
    private ModCompare.Result compare;
    private Component error;
    private boolean confirmMissing;

    public ImportScreen(Screen parent) {
        super(Component.translatable("blitzlink.import.title"), parent);
    }

    static Path importDir() {
        return Platform.get().gameDir().resolve("blitzlink-import");
    }

    @Override
    protected int panelHeight() {
        return 360;
    }

    @Override
    protected void init() {
        super.init();
        backButton();
        int x = mx + PAD, w = mw - PAD * 2;
        int top = bodyTop() + 40;
        if (step == Step.PICK) {
            scan();
            addRenderableWidget(new BlitzButton(x, bodyTop() + 10, 150, 20, Component.translatable("blitzlink.import.open_folder"), BlitzButton.Kind.DEFAULT, () -> {
                try {
                    Files.createDirectories(importDir());
                } catch (IOException ignored) {
                }
                Blaze3D.openPath(importDir());
            }));
            addRenderableWidget(new BlitzButton(x + 156, bodyTop() + 10, 80, 20, Component.translatable("blitzlink.refresh"), BlitzButton.Kind.DEFAULT, this::rebuildWidgets));
            BlitzList list = addRenderableWidget(new BlitzList(x, top, w, bodyBottom() - top - 6));
            List<BlitzList.Item> items = new ArrayList<>();
            items.add(new BlitzList.Header(Component.translatable("blitzlink.import.found")));
            if (zips.isEmpty()) items.add(new BlitzList.Note(Component.translatable("blitzlink.import.none"), BlitzTheme.MUTED));
            for (Path z : zips) items.add(new ZipRow(z));
            list.setItems(items);
        } else if (step == Step.REVIEW && analysis != null) {
            BlitzList list = addRenderableWidget(new BlitzList(x, bodyTop() + 58, w, bodyBottom() - (bodyTop() + 58) - 6));
            list.setItems(modItems());
            boolean missing = compare != null && compare.badCount() > 0;
            BlitzButton.Kind kind = missing && !confirmMissing ? BlitzButton.Kind.DANGER : BlitzButton.Kind.PRIMARY;
            Component label = Component.translatable(missing && !confirmMissing ? "blitzlink.import.open_anyway" : "blitzlink.import.open");
            addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 140, footerY(), 140, 20, label, kind, () -> {
                if (missing && !confirmMissing) {
                    confirmMissing = true;
                    rebuildWidgets();
                } else {
                    install();
                }
            }));
            addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 140 - 6 - 80, footerY(), 80, 20, Component.translatable("blitzlink.import.other"), BlitzButton.Kind.DEFAULT, () -> {
                step = Step.PICK;
                analysis = null;
                compare = null;
                confirmMissing = false;
                rebuildWidgets();
            }));
        }
    }

    private void scan() {
        try {
            Files.createDirectories(importDir());
            try (Stream<Path> s = Files.list(importDir())) {
                zips = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).sorted().toList();
            }
        } catch (IOException e) {
            zips = List.of();
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        files.stream().filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).findFirst().ifPresentOrElse(this::analyze,
                () -> error = Component.translatable("blitzlink.import.not_zip"));
    }

    private void analyze(Path zip) {
        step = Step.ANALYZING;
        error = null;
        rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try {
                return ServerZipImport.analyze(zip);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((a, err) -> minecraft.execute(() -> {
            if (err != null) {
                step = Step.PICK;
                String reason = err.getCause() != null && err.getCause().getMessage() != null ? err.getCause().getMessage() : "unknown";
                error = Component.translatableWithFallback("blitzlink.import.error." + reason, reason);
            } else {
                analysis = a;
                ServerInfo asServer = new ServerInfo(a.worldName(), a.motd(), "", 0, "vanilla", "", 0, 0, true, a.mods());
                compare = ModCompare.compare(asServer, Platform.get().loaderName(), 0, Platform.get().mods());
                step = Step.REVIEW;
            }
            rebuildWidgets();
        }));
    }

    private void install() {
        ServerZipImport.Analysis a = analysis;
        if (a == null) return;
        step = Step.INSTALLING;
        rebuildWidgets();
        Path saves = minecraft.getLevelSource().getBaseDir();
        CompletableFuture.supplyAsync(() -> {
            try {
                return ServerZipImport.install(a, saves);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }).whenComplete((levelId, err) -> minecraft.execute(() -> {
            if (err != null) {
                BlitzLink.LOGGER.error("BlitzLink: import failed", err);
                step = Step.REVIEW;
                error = Component.translatable("blitzlink.import.failed", String.valueOf(err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
                rebuildWidgets();
                return;
            }
            BlitzLinkClient.hostNextWorld();
            minecraft.createWorldOpenFlows().openWorld(levelId, () -> {
                BlitzLinkClient.cancelHostNextWorld();
                minecraft.gui.setScreen(this);
            });
        }));
    }

    private List<BlitzList.Item> modItems() {
        List<BlitzList.Item> items = new ArrayList<>();
        if (compare == null) return items;
        long bad = compare.badCount();
        items.add(new BlitzList.Header(Component.translatable("blitzlink.import.mods"),
                bad > 0 ? Component.translatable("blitzlink.import.mods_missing", bad) : Component.translatable("blitzlink.mods.count", compare.server().size())));
        if (compare.server().isEmpty()) items.add(new BlitzList.Note(Component.translatable("blitzlink.mods.none"), BlitzTheme.MUTED));
        for (ModCompare.Row r : compare.server()) items.add(new ModRowItem(r));
        return items;
    }

    private record ModRowItem(ModCompare.Row r) implements BlitzList.Item {
        @Override
        public int height() {
            return 20;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            boolean bad = r.bad();
            g.fill(x, y, x + w, y + 20, bad ? BlitzTheme.ROW_BAD : BlitzTheme.ROW);
            if (bad) {
                BlitzTheme.border(g, x, y, w, 20, BlitzTheme.RED);
                BlitzTheme.leftBar(g, x, y, 20, BlitzTheme.RED_BRIGHT);
            }
            String mark = bad ? "✕" : r.status() == ModCompare.Status.OK ? "✓" : "·";
            int mc = bad ? BlitzTheme.RED_BRIGHT : r.status() == ModCompare.Status.OK ? BlitzTheme.OLIVE_BRIGHT : BlitzTheme.MUTED;
            BlitzTheme.text(g, f, BlitzTheme.ui(Component.literal(mark)), x + 8, y + 6, mc);
            Component d = BlitzTheme.ui(switch (r.status()) {
                case MISSING -> Component.translatable("blitzlink.import.mod_missing", r.serverVersion());
                case VERSION -> Component.translatable("blitzlink.mods.version", r.serverVersion(), r.clientVersion());
                case OK -> Component.literal(r.serverVersion());
                default -> Component.translatable("blitzlink.mods.not_needed", r.serverVersion());
            });
            BlitzTheme.textRight(g, f, d, x + w - 8, y + 6, bad ? BlitzTheme.RED_BRIGHT : BlitzTheme.KHAKI);
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(r.name())), w - 40 - f.width(d)), x + 20, y + 6, BlitzTheme.PAPER);
        }
    }

    private final class ZipRow implements BlitzList.Item {
        private final Path zip;

        ZipRow(Path zip) {
            this.zip = zip;
        }

        @Override
        public int height() {
            return 18;
        }

        @Override
        public boolean clickable() {
            return true;
        }

        @Override
        public void click(double lx, double ly, int w) {
            analyze(zip);
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            BlitzList.rowFrame(g, x, y, w, 18, hovered, BlitzTheme.ROW);
            String size;
            try {
                size = String.format(Locale.ROOT, "%.1f MB", Files.size(zip) / 1048576.0);
            } catch (IOException e) {
                size = "?";
            }
            Component s = BlitzTheme.ui(Component.literal(size));
            BlitzTheme.textRight(g, f, s, x + w - 6, y + 5, BlitzTheme.MUTED);
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(zip.getFileName().toString())), w - 20 - f.width(s)), x + 6, y + 5, BlitzTheme.TEXT);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = mx + PAD, w = mw - PAD * 2;
        switch (step) {
            case PICK -> {
                g.textWithWordWrap(font, BlitzTheme.ui(Component.translatable("blitzlink.import.help")), x + 244, bodyTop() + 10, w - 244, BlitzTheme.MUTED, false);
            }
            case ANALYZING, INSTALLING -> {
                Component t = Component.translatable(step == Step.ANALYZING ? "blitzlink.import.analyzing" : "blitzlink.import.installing");
                BlitzTheme.text(g, font, BlitzTheme.display(t), x, bodyTop() + 24, BlitzTheme.PAPER);
                long tick = System.currentTimeMillis() / 40 % 100;
                g.fill(x, bodyTop() + 44, x + w, bodyTop() + 50, BlitzTheme.TRACK);
                int bx = x + (int) (w * tick / 100.0);
                g.fill(bx, bodyTop() + 44, Math.min(x + w, bx + w / 5), bodyTop() + 50, BlitzTheme.BRASS);
            }
            case REVIEW -> {
                if (analysis != null) {
                    BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.display(Component.literal(analysis.worldName())), w), x, bodyTop() + 10, BlitzTheme.PAPER);
                    String where = analysis.worldRoot().isEmpty() ? "/" : analysis.worldRoot();
                    String info = analysis.zip().getFileName() + " · " + where + String.format(Locale.ROOT, " · %.0f MB", analysis.bytes() / 1048576.0);
                    BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.ui(Component.literal(info)), w), x, bodyTop() + 26, BlitzTheme.MUTED);
                    if (!analysis.motd().isBlank()) {
                        BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.ui(Component.literal(analysis.motd().replaceAll("§.", ""))), w), x, bodyTop() + 38, BlitzTheme.KHAKI);
                    }
                    if (confirmMissing) {
                        BlitzTheme.text(g, font, BlitzTheme.ui(Component.translatable("blitzlink.import.missing_warn")), x, footerY() + 6, BlitzTheme.RED_BRIGHT);
                    }
                }
            }
        }
        if (error != null) BlitzTheme.text(g, font, BlitzTheme.ui(error), x, footerY() + 6, BlitzTheme.RED_BRIGHT);
    }
}
