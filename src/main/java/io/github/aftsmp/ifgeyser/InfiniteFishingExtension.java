package io.github.aftsmp.ifgeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.connection.SessionConnectedEvent;
import org.geysermc.geyser.api.event.connection.SessionDisconnectedEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

/**
 * Entry point for the InfiniteFishing Bedrock UI Geyser Extension.
 *
 * Geyser will instantiate this class and call lifecycle methods.
 * All heavy lifting is delegated to {@link FishingBarManager}.
 */
public class InfiniteFishingExtension implements Extension {

    private FishingBarManager barManager;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        this.barManager = new FishingBarManager(this);
        logger().info("InfiniteFishing Bedrock UI Extension enabled.");
        logger().info("Boss bar will show fishing progress for Bedrock players.");
    }

    @Subscribe
    public void onSessionConnect(SessionConnectedEvent event) {
        barManager.onConnect(event.connection());
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectedEvent event) {
        barManager.onDisconnect(event.connection());
    }
}
