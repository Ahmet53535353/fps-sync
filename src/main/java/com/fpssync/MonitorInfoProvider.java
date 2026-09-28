package com.fpssync;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDL_DisplayMode;
import static org.lwjgl.sdl.SDLVideo.*;

public class MonitorInfoProvider {

    private static int lastDisplayId = 0;
    private static int lastRefreshRate = 60;
    private static long lastCheckTime = 0;

    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;

    // Checks for monitor changes
    public static void updateDisplayInfo() {
        long now = System.nanoTime();

        if (now - lastCheckTime < CHECK_INTERVAL_NS) {
            return;
        }

        lastCheckTime = now;
        Minecraft client = Minecraft.getInstance();

        long window = client.getWindow().handle();
        int displayId = SDL_GetDisplayForWindow(window);

        if (displayId == 0) {
            return;
        }

        if (displayId != lastDisplayId) {
            lastRefreshRate = detectRefreshRate(displayId);
            lastDisplayId = displayId;
        }
    }

    public static int getRefreshRate() {
        return lastRefreshRate;
    }

    private static int detectRefreshRate(int displayId) {
        SDL_DisplayMode mode = SDL_GetCurrentDisplayMode(displayId);

        if (mode == null) {
            return 60;
        }

        return Math.round(mode.refresh_rate());
    }
}