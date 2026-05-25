package io.github.aftsmp.ifgeyser;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.ExtensionLogger;

/**
 * Netty outbound handler that intercepts SetTitlePackets containing
 * InfiniteFishing bitmap-font bar characters and replaces them with a
 * Bedrock boss bar showing the fishing pointer position and zone state.
 *
 * ── Real CloudburstMC Protocol 3.x API (verified from source) ─────────────
 *
 * BossEventPacket fields (all via Lombok @Data setters):
 *   setBossUniqueEntityId(long)
 *   setAction(BossEventPacket.Action)   — enum: CREATE, REMOVE, REGISTER_PLAYER,
 *                                         UNREGISTER_PLAYER, UPDATE_PERCENTAGE,
 *                                         UPDATE_NAME, UPDATE_PROPERTIES, UPDATE_STYLE
 *   setPlayerUniqueEntityId(long)       — needed for REGISTER_PLAYER
 *   setTitle(CharSequence)
 *   setHealthPercentage(float)
 *   setDarkenSky(int)                   — 0 = off  (NOT boolean)
 *   setColor(int)                       — 0=Pink 1=Blue 2=Red 3=Green 4=Yellow 5=Purple 6=White
 *   setOverlay(int)                     — 0=solid bar 1-4=notched variants
 *
 * SetTitlePacket.Type enum values:
 *   CLEAR, RESET, TITLE, SUBTITLE, ACTIONBAR (no underscore!), TIMES,
 *   TITLE_JSON, SUBTITLE_JSON, ACTIONBAR_JSON
 *
 * ── InfiniteFishing character map (from assets/infinitefishing/font/default.json) ──
 *   U+B001           pointer
 *   U+B002–U+B00A    bar1–bar9
 *   U+B00B           bar_rainbow
 *   U+B00C           bar10
 *   U+B00D–U+B010    fish icons
 *   U+B011           bar11 (golden border)
 *   U+B012           judgment_area_easy   ← pointer IN catch zone (easy)
 *   U+B013           judgment_area_normal ← pointer IN catch zone (normal)
 *   U+B014           judgment_area_hard   ← pointer IN catch zone (hard)
 *   U+B015–U+B019    bar_color_1–5        (pointer positions 1-5/9)
 *   U+B020–U+B023    bar_color_6–9        (positions 6-9/9; gap B01A-B01F unused)
 *   U+B024           bar_color_rainbow
 *   U+F801–U+F818    offset spacer chars  (negative/positive advance widths)
 */
public class TitleInterceptor extends ChannelOutboundHandlerAdapter {

    // ── Character constants ─────────────────────────────────────────────────
    private static final char BAR1          = '\uB002';
    private static final char BAR9          = '\uB00A';
    private static final char BAR_RAINBOW   = '\uB00B';
    private static final char JUDGMENT_EASY = '\uB012';
    private static final char JUDGMENT_HARD = '\uB014';
    private static final char BAR_COLOR_1   = '\uB015';
    private static final char BAR_COLOR_5   = '\uB019';
    private static final char BAR_COLOR_6   = '\uB020';
    private static final char BAR_COLOR_9   = '\uB023';
    private static final char BAR_COLOR_RBW = '\uB024';
    private static final char OFFSET_MIN    = '\uF801';
    private static final char OFFSET_MAX    = '\uF818';

    // ── Boss bar color int constants (CloudburstMC Protocol 3.x) ───────────
    private static final int COLOR_RED    = 2;
    private static final int COLOR_GREEN  = 3;
    private static final int COLOR_YELLOW = 4;
    private static final int COLOR_PURPLE = 5;
    private static final int OVERLAY_PROGRESS = 0; // solid bar

    // ── Boss bar entity ID ──────────────────────────────────────────────────
    // Arbitrary large ID unlikely to collide with real server entities
    private static final long BOSS_ENTITY_ID = 0xFEEDF157L;

    private final GeyserConnection connection;
    private final ExtensionLogger logger;
    private boolean bossBarActive = false;

    public TitleInterceptor(GeyserConnection connection, ExtensionLogger logger) {
        this.connection = connection;
        this.logger = logger;
    }

    // ── Netty intercept ─────────────────────────────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof SetTitlePacket title) {
            handleTitle(ctx, title, promise);
            return;
        }
        super.write(ctx, msg, promise);
    }

    // ── Core logic ───────────────────────────────────────────────────────────

    private void handleTitle(ChannelHandlerContext ctx, SetTitlePacket title,
                             ChannelPromise promise) throws Exception {
        SetTitlePacket.Type type = title.getType();

        // Clear / Reset → hide boss bar
        if (type == SetTitlePacket.Type.CLEAR || type == SetTitlePacket.Type.RESET) {
            if (bossBarActive) sendBossBarRemove(ctx);
            super.write(ctx, title, promise);
            return;
        }

        // Only inspect text-carrying types (ACTIONBAR has no underscore in real API)
        if (type != SetTitlePacket.Type.TITLE
                && type != SetTitlePacket.Type.SUBTITLE
                && type != SetTitlePacket.Type.ACTIONBAR) {
            super.write(ctx, title, promise);
            return;
        }

        String text = title.getText();
        if (text == null || !containsBarChar(text)) {
            super.write(ctx, title, promise);
            return;
        }

        // Fishing bar detected — update boss bar
        int position = extractBarPosition(text);
        boolean inZone = containsJudgmentArea(text);
        boolean rainbow = text.indexOf(BAR_COLOR_RBW) >= 0 || text.indexOf(BAR_RAINBOW) >= 0;

        if (position >= 1 || rainbow) {
            float progress = rainbow ? 1.0f : position / 9.0f;
            sendBossBarUpdate(ctx, progress, inZone, rainbow);
        }

        // Strip bitmap chars — keep any plain-text words (fish names, instructions)
        String clean = stripBitmapChars(text);
        if (!clean.isBlank()) {
            SetTitlePacket cleaned = cloneWithText(title, clean);
            super.write(ctx, cleaned, promise);
        } else {
            // Packet was pure bitmap — suppress it to avoid blank-flash on screen
            promise.setSuccess();
        }
    }

    // ── Boss bar helpers ─────────────────────────────────────────────────────

    private void sendBossBarUpdate(ChannelHandlerContext ctx,
                                   float progress, boolean inZone, boolean rainbow) {
        int color;
        String barTitle;
        if (rainbow) {
            color    = COLOR_PURPLE;
            barTitle = "\u00a7d\u2736 \u00a7lSPECIAL CATCH! \u00a7d\u2736";
        } else if (inZone) {
            color    = COLOR_GREEN;
            barTitle = "\u00a7a\u25b6  REEL IN NOW!  \u25c4";
        } else if (progress < 0.15f || progress > 0.85f) {
            color    = COLOR_RED;
            barTitle = "\u00a7c\u25c4 \u00a77Fishing \u2014 wait for green \u00a7c\u25b6";
        } else {
            color    = COLOR_YELLOW;
            barTitle = "\u00a7e\u25c4  Fishing \u2014 wait for green  \u25b6";
        }

        float clamped = Math.max(0.0f, Math.min(1.0f, progress));

        if (!bossBarActive) {
            // Step 1: CREATE the boss bar
            BossEventPacket create = new BossEventPacket();
            create.setBossUniqueEntityId(BOSS_ENTITY_ID);
            create.setAction(BossEventPacket.Action.CREATE);
            create.setTitle(barTitle);
            create.setHealthPercentage(clamped);
            create.setColor(color);
            create.setOverlay(OVERLAY_PROGRESS);
            create.setDarkenSky(0);
            writePacket(ctx, create);

            // Step 2: REGISTER_PLAYER so this client actually sees it
            BossEventPacket register = new BossEventPacket();
            register.setBossUniqueEntityId(BOSS_ENTITY_ID);
            register.setAction(BossEventPacket.Action.REGISTER_PLAYER);
            register.setPlayerUniqueEntityId(1L); // 1 = local player entity ID on Bedrock
            writePacket(ctx, register);

            bossBarActive = true;
        } else {
            // Update progress
            BossEventPacket pct = new BossEventPacket();
            pct.setBossUniqueEntityId(BOSS_ENTITY_ID);
            pct.setAction(BossEventPacket.Action.UPDATE_PERCENTAGE);
            pct.setHealthPercentage(clamped);
            writePacket(ctx, pct);

            // Update color/overlay
            BossEventPacket style = new BossEventPacket();
            style.setBossUniqueEntityId(BOSS_ENTITY_ID);
            style.setAction(BossEventPacket.Action.UPDATE_STYLE);
            style.setColor(color);
            style.setOverlay(OVERLAY_PROGRESS);
            writePacket(ctx, style);

            // Update title text
            BossEventPacket name = new BossEventPacket();
            name.setBossUniqueEntityId(BOSS_ENTITY_ID);
            name.setAction(BossEventPacket.Action.UPDATE_NAME);
            name.setTitle(barTitle);
            writePacket(ctx, name);
        }
    }

    private void sendBossBarRemove(ChannelHandlerContext ctx) {
        BossEventPacket remove = new BossEventPacket();
        remove.setBossUniqueEntityId(BOSS_ENTITY_ID);
        remove.setAction(BossEventPacket.Action.REMOVE);
        writePacket(ctx, remove);
        bossBarActive = false;
    }

    private void writePacket(ChannelHandlerContext ctx, BedrockPacket packet) {
        ctx.write(packet, ctx.newPromise());
    }

    /** Copies a SetTitlePacket but replaces the text. */
    private SetTitlePacket cloneWithText(SetTitlePacket src, String newText) {
        SetTitlePacket p = new SetTitlePacket();
        p.setType(src.getType());
        p.setText(newText);
        p.setFilteredTitleText(newText);
        p.setFadeInTime(src.getFadeInTime());
        p.setStayTime(src.getStayTime());
        p.setFadeOutTime(src.getFadeOutTime());
        p.setXuid(src.getXuid());
        p.setPlatformOnlineId(src.getPlatformOnlineId());
        return p;
    }

    // ── Text analysis helpers ────────────────────────────────────────────────

    static boolean containsBarChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uB001' && c <= '\uB024') return true;
        }
        return false;
    }

    /**
     * Returns pointer position 1-9 from bar_color chars, or from plain bar1-9.
     * bar_color_1-5 = B015-B019, bar_color_6-9 = B020-B023 (B01A-B01F unused).
     */
    static int extractBarPosition(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= BAR_COLOR_1 && c <= BAR_COLOR_5) return (c - BAR_COLOR_1) + 1;
            if (c >= BAR_COLOR_6 && c <= BAR_COLOR_9) return (c - BAR_COLOR_6) + 6;
        }
        // Fallback to plain bars
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= BAR1 && c <= BAR9) return (c - BAR1) + 1;
        }
        return -1;
    }

    /** Judgment area chars appear when the pointer overlaps the catch zone. */
    static boolean containsJudgmentArea(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= JUDGMENT_EASY && c <= JUDGMENT_HARD) return true;
        }
        return false;
    }

    /** Removes all bitmap-font and offset-spacer chars, keeping plain text. */
    static String stripBitmapChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\uB001' && c <= '\uB024') || (c >= OFFSET_MIN && c <= OFFSET_MAX)) continue;
            sb.append(c);
        }
        return sb.toString().strip();
    }
}
