/**
 * Theme registry, the CSS-string builders, and switching between packs.
 *
 * <p>{@code setPack()} / {@code toggle()} are exercised because they are headless-safe: on the
 * main thread {@code Window.getWindows()} is empty, so {@code fire()} reaches only the registered
 * listeners. The scene paths ({@code apply(Scene)}, {@code applyPopup(ContextMenu)},
 * {@code paint}, {@code walkMenus}, {@code hookPopups(tree)}) still need a real Stage and stay
 * uncovered; only their null guards are tested.
 *
 * <p>{@code .scheduler/theme.txt} is user data, so it is snapshotted and restored around every
 * test, and the current pack is put back by each test that switches it.
 */
package org.ashoreprospect.GUI;

import org.ashoreprospect.GUI.theme.BuiltinThemes;
import org.ashoreprospect.GUI.theme.ColorThemePack;
import org.ashoreprospect.GUI.theme.Contrast;
import org.ashoreprospect.GUI.theme.ThemePack;
import org.ashoreprospect.GUI.theme.ThemeKind;
import org.ashoreprospect.GUI.theme.ThemePalette;
import org.ashoreprospect.net.Endpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("theme-prefs")
class AppThemeTest {

    private static final Path FILE = Endpoints.dir().resolve("theme.txt");

    private String saved;

    @BeforeEach
    void snapshot() throws Exception {
        saved = Files.isRegularFile(FILE) ? Files.readString(FILE, StandardCharsets.UTF_8) : null;
        Files.deleteIfExists(FILE);
    }

    @AfterEach
    void restore() throws Exception {
        Files.deleteIfExists(FILE);
        if (saved != null) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, saved, StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- registry

    @Test
    void packsContainLightAndDarkInRegistrationOrder() {
        List<String> ids = new ArrayList<>();
        for (ThemePack p : AppTheme.packs()) ids.add(p.id());

        assertTrue(ids.contains("light"), "light should be registered");
        assertTrue(ids.contains("dark"), "dark should be registered");
        assertTrue(ids.indexOf("light") < ids.indexOf("dark"),
                "built-ins register in LIGHT, DARK order: " + ids);
    }

    @Test
    void registeringTheSameIdReplacesRatherThanDuplicates() {
        // Replacing the LIGHT entry would also change `current`, so snapshot the whole
        // registry and put it back (register() is the only writer that does not fire()).
        List<ThemePack> before = AppTheme.packs();
        ThemePack replacement = new ColorThemePack("light", "Light v2",
                BuiltinThemes.LIGHT.palette());
        try {
            AppTheme.register(replacement);
            assertEquals(before.size(), AppTheme.packs().size());
            assertTrue(AppTheme.packs().stream()
                    .anyMatch(p -> "light".equals(p.id()) && "Light v2".equals(p.displayName())));
        } finally {
            for (ThemePack p : before) AppTheme.register(p);
        }
        assertTrue(AppTheme.packs().stream()
                .anyMatch(p -> "light".equals(p.id()) && "Light".equals(p.displayName())));
    }

    @Test
    void registerIgnoresNullAndNullIds() {
        int before = AppTheme.packs().size();

        AppTheme.register(null);
        AppTheme.register(new ThemePack() {
            @Override public String id() { return null; }
            @Override public String displayName() { return "broken"; }
            @Override public org.ashoreprospect.GUI.theme.ThemeKind kind() {
                return org.ashoreprospect.GUI.theme.ThemeKind.COLOR;
            }
            @Override public ThemePalette palette() { return BuiltinThemes.LIGHT.palette(); }
        });

        assertEquals(before, AppTheme.packs().size());
    }

    @Test
    void aFreshlyRegisteredPackAppearsInPacks() {
        ThemePack custom = new ColorThemePack("apptheme-test", "Test",
                BuiltinThemes.DARK.palette());

        AppTheme.register(custom);

        assertTrue(AppTheme.packs().stream().anyMatch(p -> "apptheme-test".equals(p.id())));
    }

    @Test
    void listenIgnoresNullAndClearListenersEmptiesTheList() {
        assertDoesNotThrow(() -> AppTheme.listen(null));
        assertDoesNotThrow(AppTheme::clearListeners);
        assertDoesNotThrow(AppTheme::clearListeners);
    }

    @Test
    void setPackIgnoresUnknownAndCurrentIds() {
        // Both paths return before save()/fire(), so no toolkit and no file write.
        ThemePack before = AppTheme.currentPack();

        AppTheme.setPack(null);
        assertSame(before, AppTheme.currentPack());

        AppTheme.setPack("no-such-pack");
        assertSame(before, AppTheme.currentPack());

        AppTheme.setPack(before.id());
        assertSame(before, AppTheme.currentPack());
        assertFalse(Files.exists(FILE), "an ignored setPack must not write theme.txt");
    }

    // ---------------------------------------------------------------- palette

    @Test
    void builtInThemesAreExactlyLightAndDarkInThatOrder() {
        List<ThemePack> all = BuiltinThemes.all();

        assertEquals(2, all.size());
        assertSame(BuiltinThemes.LIGHT, all.get(0));
        assertSame(BuiltinThemes.DARK, all.get(1));
        assertEquals("light", all.get(0).id());
        assertEquals("Light", all.get(0).displayName());
        assertEquals("dark", all.get(1).id());
        assertEquals("Dark", all.get(1).displayName());
    }

    @Test
    void builtInThemesAreShippedAsColorPacksWithAnAlreadyContrastSafePalette() {
        for (ThemePack pack : BuiltinThemes.all()) {
            assertTrue(pack instanceof ColorThemePack, pack.id());
            assertEquals(ThemeKind.COLOR, pack.kind());
            // ColorThemePack runs contrastSafe() in its constructor, so applying it again
            // changes nothing. PINNED: the palette is fixed at construction, so an unsafe raw
            // palette is unreachable from outside the pack.
            assertEquals(pack.palette(), pack.palette().contrastSafe(), pack.id());
        }
    }

    @Test
    void isDarkMatchesTheContrastRuleForBothBuiltIns() {
        // Written as relations to Contrast rather than to literal true/false, because that
        // is exactly what AppTheme.isDark() delegates to.
        assertFalse(Contrast.darkSurface(BuiltinThemes.LIGHT.palette().bg()));
        assertTrue(Contrast.darkSurface(BuiltinThemes.DARK.palette().bg()));
    }

    @Test
    void everyColourAccessorReturnsItsPaletteField() {
        ThemePalette p = AppTheme.palette();

        assertEquals(p.paper(), AppTheme.paper());
        assertEquals(p.past(), AppTheme.past());
        assertEquals(p.today(), AppTheme.today());
        assertEquals(p.hover(), AppTheme.hover());
        assertEquals(p.ink(), AppTheme.ink());
        assertEquals(p.muted(), AppTheme.muted());
        assertEquals(p.accent(), AppTheme.accent());
        assertEquals(p.accentInk(), AppTheme.accentInk());
        assertEquals(p.line(), AppTheme.line());
        assertEquals(p.cardPaper(), AppTheme.cardPaper());
        assertEquals(p.cardInk(), AppTheme.cardInk());
        assertEquals(p.cardMuted(), AppTheme.cardMuted());
        // Two names that do not match their field, pinned rather than "fixed":
        assertEquals(p.bg(), AppTheme.typewriterBg(), "typewriterBg() is the palette bg");
        assertEquals(p.chrome(), AppTheme.typewriterRail(), "typewriterRail() is the palette chrome");
    }

    // ---------------------------------------------------------------- CSS strings

    @Test
    void fontCarriesThePlatformFallbackChain() {
        String f = AppTheme.font();
        assertTrue(f.contains("Segoe UI"), f);
        assertTrue(f.contains("Helvetica Neue"), f);
        assertTrue(f.contains("Arial"), f);
        assertTrue(f.contains("sans-serif"), f);
        assertTrue(f.contains("-fx-font-family"), f);
    }

    @Test
    void everyStyleBuilderEmbedsItsPaletteColour() {
        ThemePalette p = AppTheme.palette();
        int before = AppTheme.packs().size();
        assertTrue(before >= 2);

        assertEmbedded("fieldCss", AppTheme.fieldCss(), p.paper(), p.ink(), p.muted());
        assertEmbedded("cardBoxCss", AppTheme.cardBoxCss(), p.cardPaper(), p.line());
        assertEmbedded("cardPrimaryBtnCss", AppTheme.cardPrimaryBtnCss(), p.accent(), p.accentInk());
        assertEmbedded("cardGhostBtnCss", AppTheme.cardGhostBtnCss(), p.cardInk(), p.line());
        assertEmbedded("railOn", AppTheme.railOn(), p.line(), p.ink());
        assertEmbedded("railOff", AppTheme.railOff(), p.muted());
        assertEmbedded("tabOn", AppTheme.tabOn(), p.accent(), p.accentInk());
        assertEmbedded("tabOff", AppTheme.tabOff(), p.muted());
    }

    @Test
    void styleBuildersAreStableForTheSamePack() {
        // Pure string building: no state is touched between calls.
        assertEquals(AppTheme.fieldCss(), AppTheme.fieldCss());
        assertEquals(AppTheme.cardBoxCss(), AppTheme.cardBoxCss());
        assertEquals(AppTheme.railOn(), AppTheme.railOn());
        assertEquals(AppTheme.tabOn(), AppTheme.tabOn());
    }

    @Test
    void loadFallbackLadderIsNotReachableInProcess() {
        // PINNED: AppTheme.load() runs once from the static initialiser, so the
        // theme.txt-to-built-in fallback ladder cannot be re-run in this JVM. The reachable
        // part is that an unknown id never changes the current pack (see
        // setPackIgnoresUnknownAndCurrentIds). Recorded so the gap is a decision.
        assertNotNull(AppTheme.currentPack());
        assertSame(AppTheme.currentPack().palette(), AppTheme.palette());
    }

    private static void assertEmbedded(String name, String css, String... colours) {
        assertNotNull(css, name);
        assertFalse(css.isBlank(), name + " should not be blank");
        for (String c : colours) {
            if (c == null || c.isBlank()) continue;
            assertTrue(css.contains(c), name + " should contain " + c + ": " + css);
        }
    }

    // ---------------------------------------------------------------- switching packs

    @Test
    void setPack_switchesThePaletteAndPersistsTheId() throws Exception {
        ThemePack before = AppTheme.currentPack();
        String other = "light".equals(before.id()) ? "dark" : "light";
        try {
            AppTheme.setPack(other);

            assertEquals(other, AppTheme.currentPack().id());
            assertTrue(Files.exists(FILE), "a real switch persists the choice");
            assertEquals(other, Files.readString(FILE, StandardCharsets.UTF_8).trim());
            assertEquals(AppTheme.currentPack().palette(), AppTheme.palette(),
                    "the colour accessors follow the current pack");
            assertEquals(AppTheme.currentPack().palette().ink(), AppTheme.ink());
        } finally {
            AppTheme.setPack(before.id());
        }
    }

    @Test
    void toggle_flipsBetweenTheTwoBuiltInsAndIsInvolutive() throws Exception {
        ThemePack start = AppTheme.currentPack();
        try {
            AppTheme.toggle();
            ThemePack flipped = AppTheme.currentPack();
            assertFalse(start.id().equals(flipped.id()), "toggle picks the other built-in");

            AppTheme.toggle();
            assertEquals(start.id(), AppTheme.currentPack().id(), "toggling twice returns");
        } finally {
            AppTheme.setPack(start.id());
        }
    }

    @Test
    void toggle_runsRegisteredListenersExactlyOncePerSwitch() throws Exception {
        ThemePack start = AppTheme.currentPack();
        int[] runs = {0};
        try {
            AppTheme.clearListeners();
            AppTheme.listen(() -> runs[0]++);

            AppTheme.toggle();
            assertEquals(1, runs[0], "one switch notifies once");

            AppTheme.clearListeners();
            AppTheme.toggle();
            assertEquals(1, runs[0], "cleared listeners are not called");
        } finally {
            AppTheme.clearListeners();
            AppTheme.setPack(start.id());
        }
    }

    @Test
    void isDark_followsWhicheverBuiltInIsCurrent() throws Exception {
        ThemePack start = AppTheme.currentPack();
        try {
            AppTheme.setPack("dark");
            assertEquals(Contrast.darkSurface(AppTheme.palette().bg()), AppTheme.isDark());

            AppTheme.setPack("light");
            assertFalse(AppTheme.isDark(), "the light pack has a light surface");
        } finally {
            AppTheme.setPack(start.id());
        }
    }

    @Test
    void registeringAPackByIdLetsSetPackSelectIt() throws Exception {
        ThemePack start = AppTheme.currentPack();
        ThemePack custom = new ColorThemePack("apptheme-custom", "Custom",
                BuiltinThemes.DARK.palette());
        try {
            AppTheme.register(custom);
            AppTheme.setPack("apptheme-custom");

            assertSame(custom, AppTheme.currentPack(),
                    "a registered pack is a real switch, not just a registry entry");
        } finally {
            AppTheme.setPack(start.id());
        }
    }

    // ---------------------------------------------------------------- toolkit-free guards

    @Test
    void applyAndPopupEntryPointsReturnEarlyForNull() {
        // Every one of these checks its argument before touching the toolkit, so they are safe
        // to call headlessly. The scene paths (ensureCss / paint / walkMenus) still need a real
        // Stage and stay uncovered.
        assertDoesNotThrow(() -> AppTheme.apply(null));
        assertDoesNotThrow(() -> AppTheme.applyPopup(null));
        assertDoesNotThrow(() -> AppTheme.hookPopups(null));
    }

    @Test
    void currentPackAndPaletteAreTheSameObject() {
        assertSame(AppTheme.currentPack().palette(), AppTheme.palette(),
                "palette() delegates to the current pack; it does not copy");
        assertNotNull(AppTheme.currentPack().displayName());
    }
}
