package io.github.aftsmp.ifgeyser;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.session.GeyserSession;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FishingBarManager {

    static final String PIPELINE_HANDLER_NAME = "ifishing-title-interceptor";

    private final InfiniteFishingExtension extension;
    private final Map<UUID, TitleInterceptor> interceptors = new ConcurrentHashMap<>();

    public FishingBarManager(InfiniteFishingExtension extension) {
        this.extension = extension;
    }

    public void onConnect(GeyserConnection connection) {
        try {
            Channel channel = resolveChannel(connection);
            if (channel == null) {
                extension.logger().warning(
                    "Could not resolve Netty channel for " + connection.name()
                    + " — fishing boss bar will not work for this player."
                    + " Please open an issue with your Geyser build version.");
                return;
            }

            // javaUuid() is defined on the parent org.geysermc.api.connection.Connection interface
            UUID uuid = connection.javaUuid();
            TitleInterceptor interceptor = new TitleInterceptor(connection, extension.logger());
            interceptors.put(uuid, interceptor);

            // Register cleanup when the channel closes (covers all disconnect scenarios)
            channel.closeFuture().addListener(future -> interceptors.remove(uuid));

            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(PIPELINE_HANDLER_NAME) != null) return;
                    String insertBefore = findEncoderName(channel);
                    if (insertBefore != null) {
                        channel.pipeline().addBefore(insertBefore, PIPELINE_HANDLER_NAME, interceptor);
                    } else {
                        channel.pipeline().addFirst(PIPELINE_HANDLER_NAME, interceptor);
                    }
                    extension.logger().debug("Installed title interceptor for " + connection.name());
                } catch (Exception ex) {
                    extension.logger().warning(
                        "Failed to install pipeline handler for " + connection.name()
                        + ": " + ex.getMessage());
                }
            });

        } catch (Exception e) {
            extension.logger().warning("onConnect failed for " + connection.name() + ": " + e);
        }
    }

    // ── Channel resolution ────────────────────────────────────────────────────

    /**
     * Walks the internal GeyserSession → UpstreamSession → BedrockSession → Channel
     * object graph via reflection, trying several method name variants to stay
     * compatible across Geyser / CloudburstMC Protocol versions.
     */
    private Channel resolveChannel(GeyserConnection connection) {
        try {
            // Cast to the internal impl — widely used by extensions
            GeyserSession session = (GeyserSession) connection;
            Object upstream = session.getUpstream();

            // Path 1: getSession() → getPeer() → getChannel()  (CloudburstMC Protocol 3.x)
            try {
                Object bedrockSession = invoke(upstream, "getSession");
                Object peer = invoke(bedrockSession, "getPeer");
                return (Channel) invoke(peer, "getChannel");
            } catch (Exception ignored) {}

            // Path 2: getSession() → getChannel()
            try {
                Object bedrockSession = invoke(upstream, "getSession");
                return (Channel) invoke(bedrockSession, "getChannel");
            } catch (Exception ignored) {}

            // Path 3: upstream.getChannel() directly
            try {
                return (Channel) invoke(upstream, "getChannel");
            } catch (Exception ignored) {}

            extension.logger().warning("resolveChannel: no known path worked for Geyser build. "
                + "Upstream type: " + upstream.getClass().getName());
        } catch (ClassCastException e) {
            extension.logger().warning("GeyserConnection is not GeyserSession — API changed?");
        } catch (Exception e) {
            extension.logger().warning("resolveChannel error: " + e);
        }
        return null;
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    /** Returns the name of the first encoder-like handler, or null. */
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
