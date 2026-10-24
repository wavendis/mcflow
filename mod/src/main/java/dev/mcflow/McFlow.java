package dev.mcflow;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The part of Mc'Flow that runs on both clients and servers: the multiplayer sing-along channels. Everything else is
 * client-only, in {@link McFlowClient}.
 */
@Mod(McFlow.MODID)
public class McFlow {
    public static final String MODID = "mcflow";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McFlow(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> e.registrar("1").optional()
                .playToServer(SingAlong.ShareLine.TYPE, SingAlong.ShareLine.CODEC,
                        (line, ctx) -> ctx.enqueueWork(() -> SingAlong.relay((ServerPlayer) ctx.player(), line, PacketDistributor::sendToPlayer)))
                .playToClient(SingAlong.PeerLine.TYPE, SingAlong.PeerLine.CODEC, (line, ctx) -> PeerLyrics.receive(line)));
    }

    static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
