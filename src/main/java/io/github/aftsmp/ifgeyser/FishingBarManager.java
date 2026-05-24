package io.github.aftsmp.ifgeyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.session.GeyserSession;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks connected Bedrock sessions and installs a {@link TitleInterceptor}
 * into each session's Netty pipeline so we can intercept outbound
 * SetTitlePackets before they reach the Bedrock client.
 */
public class FishingBarManager {

    /** Interceptor name used in the Netty pipeline so we can add/remove it safely. */
    static final String PIPELINE_HANDLER_NAME = "ifishing-title-interceptor";

    private final InfiniteFishingExtension extension;

    /**
     * Maps player UUID → the interceptor installed for that session.
     * Used so we can cleanly remove it on disconnect.
     */
    private final Map<UUID, TitleInterceptor> interceptors = new ConcurrentHashMap<>();

    public FishingBarManager(InfiniteFishingExtension extension) {
        this.extension = extension;
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    public void onConnect(GeyserConnection connection) {
        try {
            Channel channel = resolveChannel(connection);
            if (channel == null) {
                extension.logger().warning(
                    "Could not resolve Netty channel for " + connection.name()
                    + " — fishing boss bar will not work for this player."
                    + " Check Geyser version compatibility.");
                return;
            }

            TitleInterceptor interceptor = new TitleInterceptor(connection, extension.logger());
            interceptors.put(connection.playerUuid(), interceptor);

            /*
             * We want to sit BEFORE the packet encoder so we see real packet objects,
             * not bytes.  Walk the pipeline names and insert before the first
             * encoder-like handler we find; fall back to addFirst() (which for
             * outbound events fires last — i.e. right before the encoder).
             */
            channel.pipeline().channel().eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(PIPELINE_HANDLER_NAME) != null) {
                        return; // already installed (reconnect race)
                    }
                    String insertBefore = findEncoderName(channel);
                    if (insertBefore != null) {
                        channel.pipeline().addBefore(insertBefore, PIPELINE_HANDLER_NAME, interceptor);
                    } else {
                        channel.pipeline().addFirst(PIPELINE_HANDLER_NAME, interceptor);
                    }
                    extension.logger().debug("Installed title interceptor for " + connection.name());
                } catch (Exception ex) {
                    extension.logger().warning(
                        "Failed to install pipeline handler for " + connection.name() + ": " + ex.getMessage());
                }
            });

        } catch (Exception e) {
            extension.logger().warning("onConnect failed for " + connection.name() + ": " + e.getMessage());
        }
    }

    public void onDisconnect(GeyserConnection connection) {
        TitleInterceptor interceptor = interceptors.remove(connection.playerUuid());
        if (interceptor == null) return;

        try {
            Channel channel = resolveChannel(connection);
            if (channel != null) {
                channel.pipeline().channel().eventLoop().execute(() -> {
                    try {
                        if (channel.pipeline().get(PIPELINE_HANDLER_NAME) != null) {
                            channel.pipeline().remove(PIPELINE_HANDLER_NAME);
                        }
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the Netty {@link Channel} from a {@link GeyserConnection}.
     *
     * GeyserConnection is the public API type; the implementation is the
     * internal GeyserSession.  We walk the object graph via reflection so this
     * survives minor API shuffling between Geyser builds.
     *
     * Typical path:
     *   GeyserSession → getUpstream() → UpstreamSession
     *                → getSession()  → BedrockServerSession (CloudburstMC)
     *                → getPeer()     → BedrockPeer
     *                → getChannel()  → io.netty.channel.Channel
     */
    private Channel resolveChannel(GeyserConnection connection) {
        try {
            GeyserSession session = (GeyserSession) connection;
            Object upstream = session.getUpstream();

            // Try the most common path first (Geyser 2.x + CloudburstMC Protocol 3.x)
            try {
                Method getSession = upstream.getClass().getMethod("getSession");
                Object bedrockSession = getSession.invoke(upstream);

                // CloudburstMC Protocol 3.x: session.getPeer().getChannel()
                try {
                    Method getPeer = bedrockSession.getClass().getMethod("getPeer");
                    Object peer = getPeer.invoke(bedrockSession);
                    Method getChannel = peer.getClass().getMethod("getChannel");
                    return (Channel) getChannel.invoke(peer);
                } catch (NoSuchMethodException ignored) {}

                // Older CloudburstMC: session.getConnection().getChannel()
                try {
                    Method getConn = bedrockSession.getClass().getMethod("getConnection");
                    Object conn = getConn.invoke(bedrockSession);
                    Method getChannel = conn.getClass().getMethod("getChannel");
                    return (Channel) getChannel.invoke(conn);
                } catch (NoSuchMethodException ignored) {}

                // Last resort on session: direct getChannel()
                try {
                    Method getChannel = bedrockSession.getClass().getMethod("getChannel");
                    return (Channel) getChannel.invoke(bedrockSession);
                } catch (NoSuchMethodException ignored) {}

            } catch (NoSuchMethodException e) {
                // UpstreamSession doesn't have getSession() — try directly on upstream
                try {
                    Method getChannel = upstream.getClass().getMethod("getChannel");
                    return (Channel) getChannel.invoke(upstream);
                } catch (NoSuchMethodException ignored) {}
            }

            // Absolute fallback: search declared fields for a Channel instance
            return findChannelInFields(upstream);

        } catch (ClassCastException e) {
            extension.logger().warning("GeyserConnection is not a GeyserSession — internal API changed?");
        } catch (Exception e) {
            extension.logger().warning("resolveChannel error: " + e);
        }
        return null;
    }

    /** Recursively searches declared fields of an object for a Netty Channel. */
    private Channel findChannelInFields(Object obj) {
        if (obj == null) return null;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                try { return (Channel) f.get(obj); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Finds the name of the first encoder-like handler in the pipeline so we
     * can insert our handler just before it, ensuring we see packet objects
     * rather than already-encoded bytes.
     */
    private String findEncoderName(Channel channel) {
        List<String> names = channel.pipeline().names();
        for (String name : names) {
            String lower = name.toLowerCase();
            if (lower.contains("encoder") || lower.contains("batch") || lower.contains("compressor")) {
                return name;
            }
        }
        return null;
    }
}
