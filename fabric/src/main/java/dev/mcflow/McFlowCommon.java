package dev.mcflow;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * The part of Mc'Flow that runs on both clients and servers: the multiplayer sing-along channels. Everything else is
 * client-only, in {@link McFlow}.
 */
public class McFlowCommon implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(SingAlong.ShareLine.TYPE, SingAlong.ShareLine.CODEC);
        PayloadTypeRegistry.playS2C().register(SingAlong.PeerLine.TYPE, SingAlong.PeerLine.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SingAlong.ShareLine.TYPE,
                (line, ctx) -> SingAlong.relay(ctx.player(), line, ServerPlayNetworking::send));
    }
}
