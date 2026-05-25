package io.github.aftsmp.ifgeyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

public class InfiniteFishingExtension implements Extension {

    private FishingBarManager barManager;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        this.barManager = new FishingBarManager(this);
        logger().info("InfiniteFishing Bedrock UI Extension enabled.");
        logger().info("Boss bar will replace tiny fishing bars for Bedrock players.");
    }

    /**
     * SessionLoadResourcePacksEvent fires when the Bedrock client first connects —
     * before Java auth, so the Bedrock Netty channel is fully ready to be hooked.
     * There is no SessionConnectedEvent or SessionDisconnectedEvent in the Geyser API;
     * cleanup on disconnect is handled by the Netty channel close listener we add
     * inside FishingBarManager.onConnect().
     */
    @Subscribe
    public void onSessionConnect(SessionLoadResourcePacksEvent event) {
        barManager.onConnect(event.connection());
    }
}
