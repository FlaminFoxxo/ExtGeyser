package io.github.aftsmp.ifgeyser;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.data.BossEventColor;
import org.cloudburstmc.protocol.bedrock.data.BossEventOverlay;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.ExtensionLogger;

/**
 * Netty outbound handler installed into each Bedrock session's channel pipeline.
 *
 * What it does
 * ─────────────
 * InfiniteFishing's PointerBarMinigame sends Java title packets whose text is
 * composed of layered custom-font bitmap characters (bar_color_1 … bar_color_9,
 * judgment_area_easy/normal/hard, pointer, etc.).  On Java these render at
 * 128-pixel height and look great.  On Bedrock they are mapped to glyph_B0.png
 * cells and rendered at normal text-character height — i.e. they are tiny.
 *
 * This handler:
 *  1. Intercepts outbound SetTitlePackets that contain InfiniteFishing bar chars.
 *  2. Extracts the pointer position (bar_color_1-9) and zone state
 *     (judgment_area_easy/normal/hard).
 *  3. Sends a BossEventPacket so the player sees a large, clear progress bar.
 *  4. Strips the invisible/tiny bitmap chars from the title text so the screen
 *     isn't cluttered with garbage glyphs.
 *  5. Removes the boss bar when the title is cleared or reset.
 *
 * Character map (from assets/infinitefishing/font/default.json)
 * ─────────────────────────────────────────────────────────────
 *  U+B001           pointer
 *  U+B002–U+B00A    bar1–bar9        (blue fill, 9 positions)
 *  U+B00B           bar_rainbow
 *  U+B00C           bar10
 *  U+B00D–U+B010    fish icons
 *  U+B011           bar11            (golden border / empty frame)
 *  U+B012           judgment_area_easy    ← player IS in the catch zone (easy)
 *  U+B013           judgment_area_normal  ← player IS in the catch zone (normal)
 *  U+B014           judgment_area_hard    ← player IS in the catch zone (hard)
 *  U+B015–U+B019    bar_color_1–5    (coloured position indicators 1-5/9)
 *  U+B020–U+B023    bar_color_6–9    (coloured position indicators 6-9/9)
 *                   ↑ gap B01A-B01F is intentional — those codepoints are unused
 *  U+B024           bar_color_rainbow
 *
 *  Offset spacers (from offset_chars.json — used to position elements):
 *  U+F801–U+F808  negative advances: -3,-4,-6,-10,-18,-34,-66,-130
 *  U+F811–U+F818  positive advances: -1,+1,+3,+7,+15,+31,+63,+127
 */
public class TitleInterceptor extends ChannelOutboundHandlerAdapter {

    // ── InfiniteFishing character constants ────────────────────────────────────
    private static final char POINTER           = '\uB001';
    private static final char BAR1              = '\uB002';
    private static final char BAR9              = '\uB00A';
    private static final char BAR_RAINBOW       = '\uB00B';
    private static final char BAR10             = '\uB00C';
    private static final char FISH_ICON         = '\uB00D';
    private static final char STRUGGLING_ICON3  = '\uB010';
    private static final char BAR11             = '\uB011';
    private static final char JUDGMENT_EASY     = '\uB012';
    private static final char JUDGMENT_NORMAL   = '\uB013';
    private static final char JUDGMENT_HARD     = '\uB014';
    private static final char BAR_COLOR_1       = '\uB015'; // positions 1-5
    private static final char BAR_COLOR_5       = '\uB019';
    private static final char BAR_COLOR_6       = '\uB020'; // positions 6-9 (gap B01A-B01F unused)
    private static final char BAR_COLOR_9       = '\uB023';
    private static final char BAR_COLOR_RAINBOW = '\uB024';

    /** Offset/spacer chars used to position bitmap glyphs — strip these too. */
    private static final char OFFSET_NEG_MIN    = '\uF801';
    private static final char OFFSET_POS_MAX    = '\uF818';

    // ── Boss bar display ────────────────────────────────────────────────────────
    /**
     * Fake entity ID for our boss bar.  Bedrock boss bars are entity-based;
     * we pick a large ID that won't collide with real server entities.
     */
    // (alias below used everywhere)

    private static final long BOSS_BAR_ENTITY_ID_CLEAN = 0xFEEDF157L; // valid long literal

    private final GeyserConnection connection;
    private final ExtensionLogger logger;

    /** Whether we currently have an active boss bar on the client. */
    private boolean bossBarActive = false;

    public TitleInterceptor(GeyserConnection connection, ExtensionLogger logger) {
        this.connection = connection;
        this.logger = logger;
    }

    // ── Netty outbound intercept ────────────────────────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        /*
         * In CloudburstMC's Netty pipeline packets travel as raw BedrockPacket
         * objects until they hit the batch/encoder stage.  We just need to check
         * the type and let everything else pass through untouched.
         */
        if (msg instanceof BedrockPacket) {
            if (msg instanceof SetTitlePacket title) {
                handleTitle(ctx, title, promise);
                return; // we re-write the (possibly modified) packet ourselves
            }
        }
        super.write(ctx, msg, promise);
    }

    // ── Core logic ──────────────────────────────────────────────────────────────

    private void handleTitle(ChannelHandlerContext ctx, SetTitlePacket title, ChannelPromise promise)
            throws Exception {

        SetTitlePacket.Type type = title.getType();

        // ── Clear / Reset → remove boss bar if we showed one ──────────────────
        if (type == SetTitlePacket.Type.CLEAR || type == SetTitlePacket.Type.RESET) {
            if (bossBarActive) {
                sendBossBarRemove(ctx);
            }
            super.write(ctx, title, promise);
            return;
        }

        // ── Only inspect TITLE, SUBTITLE and ACTION_BAR ───────────────────────
        if (type != SetTitlePacket.Type.TITLE
                && type != SetTitlePacket.Type.SUBTITLE
                && type != SetTitlePacket.Type.ACTION_BAR) {
            super.write(ctx, title, promise);
            return;
        }

        String text = title.getText();
        if (text == null || !containsBarChar(text)) {
            // Not a fishing bar packet — pass through unchanged
            super.write(ctx, title, promise);
            return;
        }

        // ── Fishing bar detected ───────────────────────────────────────────────
        int    position   = extractBarColorPosition(text);   // 1-9, or -1 if not a color-bar frame
        boolean inZone    = containsJudgmentArea(text);
        boolean isRainbow = text.indexOf(BAR_COLOR_RAINBOW) >= 0 || text.indexOf(BAR_RAINBOW) >= 0;

        // Send / update the boss bar
        if (position >= 1 || isRainbow) {
            float progress = isRainbow ? 1.0f : position / 9.0f;
            sendBossBarUpdate(ctx, progress, inZone, isRainbow);
        }

        // Strip all bitmap-font chars and offset spacers from the title text
        // so the player sees only any plain-text instruction words (if any).
        String cleanText = stripBitmapChars(text);
        if (cleanText.isBlank()) {
            // Title is purely bitmap chars — suppress it entirely to avoid
            // a blank / invisible rectangle flash on screen.
            // We still let TIMES packets through so animation timing is correct.
        } else {
            // Re-send with cleaned text
            SetTitlePacket cleaned = new SetTitlePacket();
            cleaned.setType(title.getType());
            cleaned.setText(cleanText);
            cleaned.setFadeInTime(title.getFadeInTime());
            cleaned.setStayTime(title.getStayTime());
            cleaned.setFadeOutTime(title.getFadeOutTime());
            super.write(ctx, cleaned, promise);
            return;
        }

        // Suppress the original packet (it had only bitmap chars)
        promise.setSuccess();
    }

    // ── Boss bar packet helpers ─────────────────────────────────────────────────

    /**
     * Creates or updates the fishing boss bar on the client.
     *
     * @param progress  0.0 – 1.0 float representing pointer position left→right
     * @param inZone    true when the pointer is inside the judgment (catch) zone
     * @param isRainbow true for the rainbow special bar state
     */
    private void sendBossBarUpdate(ChannelHandlerContext ctx,
                                   float progress, boolean inZone, boolean isRainbow) {
        BossEventColor color;
        String titleText;

        if (isRainbow) {
            color     = BossEventColor.PURPLE;
            titleText = "§d✦ §lSPECIAL CATCH! §d✦";
        } else if (inZone) {
            color     = BossEventColor.GREEN;
            titleText = "§a▶  REEL IN NOW!  ◀";
        } else {
            // Not in zone — colour shifts to red as the pointer nears the edges
            if (progress < 0.15f || progress > 0.85f) {
                color     = BossEventColor.RED;
                titleText = "§c◀ §7Fishing — wait for green §c▶";
            } else {
                color     = BossEventColor.YELLOW;
                titleText = "§e◀  Fishing — wait for green  ▶";
            }
        }

        if (!bossBarActive) {
            // First frame: CREATE the boss bar
            BossEventPacket create = new BossEventPacket();
            create.setBossUniqueEntityId(BOSS_BAR_ENTITY_ID_CLEAN);
            create.setType(BossEventPacket.Type.SHOW);
            create.setTitle(titleText);
            create.setHealthPercentage(Math.max(0.0f, Math.min(1.0f, progress)));
            create.setColor(color);
            create.setOverlay(BossEventOverlay.PROGRESS);
            create.setDarkenSky(false);
            create.setPlayerUniqueEntityId(getPlayerEntityId());
            writePacket(ctx, create);
            bossBarActive = true;
        } else {
            // Subsequent frames: only update what changed (cheaper)
            BossEventPacket updatePct = new BossEventPacket();
            updatePct.setBossUniqueEntityId(BOSS_BAR_ENTITY_ID_CLEAN);
            updatePct.setType(BossEventPacket.Type.UPDATE_PERCENT);
            updatePct.setHealthPercentage(Math.max(0.0f, Math.min(1.0f, progress)));
            writePacket(ctx, updatePct);

            BossEventPacket updateStyle = new BossEventPacket();
            updateStyle.setBossUniqueEntityId(BOSS_BAR_ENTITY_ID_CLEAN);
            updateStyle.setType(BossEventPacket.Type.UPDATE_PROPERTIES);
            updateStyle.setColor(color);
            updateStyle.setOverlay(BossEventOverlay.PROGRESS);
            writePacket(ctx, updateStyle);

            BossEventPacket updateTitle = new BossEventPacket();
            updateTitle.setBossUniqueEntityId(BOSS_BAR_ENTITY_ID_CLEAN);
            updateTitle.setType(BossEventPacket.Type.UPDATE_NAME);
            updateTitle.setTitle(titleText);
            writePacket(ctx, updateTitle);
        }
    }

    private void sendBossBarRemove(ChannelHandlerContext ctx) {
        BossEventPacket hide = new BossEventPacket();
        hide.setBossUniqueEntityId(BOSS_BAR_ENTITY_ID_CLEAN);
        hide.setType(BossEventPacket.Type.HIDE);
        writePacket(ctx, hide);
        bossBarActive = false;
    }

    /** Writes a packet directly onto the pipeline below our handler. */
    private void writePacket(ChannelHandlerContext ctx, BedrockPacket packet) {
        ctx.write(packet, ctx.newPromise());
    }

    /** Returns the Bedrock entity ID of the player, used for REGISTER_PLAYER. */
    private long getPlayerEntityId() {
        // GeyserConnection doesn't expose entity IDs in the public API.
        // Entity ID 1 is typically the local player on the Bedrock client.
        // If you need the real geyserId, cast connection to GeyserSession and call
        // connection.getPlayerEntity().getGeyserId() — but 1 works fine for
        // boss bar registration in practice.
        return 1L;
    }

    // ── Text analysis helpers ───────────────────────────────────────────────────

    /**
     * Returns true if the string contains any InfiniteFishing bar character.
     * This is our primary trigger check — fast char-range test, no allocation.
     */
    static boolean containsBarChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Main bar range: B001–B024
            if (c >= '\uB001' && c <= '\uB024') return true;
        }
        return false;
    }

    /**
     * Returns the 1-based pointer position (1–9) encoded by bar_color_X chars.
     * Returns -1 if no bar_color char is present (e.g. only bar1-bar9 are used).
     *
     * Character layout (non-contiguous due to Hangul block gaps):
     *   B015–B019 → positions 1–5
     *   B020–B023 → positions 6–9
     */
    static int extractBarColorPosition(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= BAR_COLOR_1 && c <= BAR_COLOR_5) {
                return (c - BAR_COLOR_1) + 1; // 1-5
            }
            if (c >= BAR_COLOR_6 && c <= BAR_COLOR_9) {
                return (c - BAR_COLOR_6) + 6; // 6-9
            }
        }
        // Fall back to un-coloured bar1-bar9 if present
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= BAR1 && c <= BAR9) {
                return (c - BAR1) + 1;
            }
        }
        return -1;
    }

    /**
     * Returns true if any judgment_area char is present in the text.
     * Judgment area chars appear when the pointer overlaps the catch zone,
     * meaning the player should click/reel NOW.
     */
    static boolean containsJudgmentArea(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == JUDGMENT_EASY || c == JUDGMENT_NORMAL || c == JUDGMENT_HARD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes all InfiniteFishing bitmap-font chars and offset spacers from text,
     * leaving only any plain-text words that InfiniteFishing may have included
     * (e.g. fish names, point values).
     *
     * Chars stripped:
     *  - All bar/icon chars: U+B001–U+B024
     *  - All offset spacer chars: U+F801–U+F818
     *  - Resulting leading/trailing whitespace
     */
    static String stripBitmapChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isBitmapBar    = (c >= '\uB001' && c <= '\uB024');
            boolean isOffsetSpacer = (c >= OFFSET_NEG_MIN && c <= OFFSET_POS_MAX);
            if (!isBitmapBar && !isOffsetSpacer) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }
}
