package dev.mcflow;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.UUID;

/**
 * Multiplayer sing-along, the part that also runs on servers: a player shares the lyric line they're on, and the
 * server passes it on to everyone nearby, who see it above that player's head ({@link PeerLyrics}).
 * 1.8.9 port: FML's SimpleNetworkWrapper. A server with Mc'Flow says hello when you join; until then nothing is sent,
 * so players and servers without Mc'Flow connect and play as normal.
 */
public final class SingAlong {
    /** Lines go to players within this many blocks of the singer. */
    private static final double RANGE = 64;
    private static final int MAX_LENGTH = 200;

    static final SimpleNetworkWrapper NET = NetworkRegistry.INSTANCE.newSimpleChannel("mcflow");
    /** Client: whether the server we're on has Mc'Flow (it said hello). */
    static volatile boolean serverHasMod;

    SingAlong() {} // one instance listens for joins (server side)

    /** Both sides, at start-up: the channel's messages, in the same order everywhere. */
    static void register() {
        NET.registerMessage(ShareLine.Handler.class, ShareLine.class, 0, Side.SERVER);
        NET.registerMessage(PeerLine.Handler.class, PeerLine.class, 1, Side.CLIENT);
        NET.registerMessage(Hello.Handler.class, Hello.class, 2, Side.CLIENT);
    }

    /** Server: tell each joining player that sharing works here. */
    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP p) NET.sendTo(new Hello(), p);
    }

    /** Client to server: "this is the line I'm on now" (empty = nothing playing). */
    public static final class ShareLine implements IMessage {
        String text = "";

        public ShareLine() {}

        ShareLine(String text) {
            this.text = text;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            text = ByteBufUtils.readUTF8String(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text);
        }

        /** Server side: passes a player's line on to everyone near them in the same world (not back to them). */
        public static final class Handler implements IMessageHandler<ShareLine, IMessage> {
            @Override
            public IMessage onMessage(ShareLine line, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
                EntityPlayerMP from = ctx.getServerHandler().playerEntity;
                ((WorldServer) from.worldObj).addScheduledTask(() -> {
                    String text = line.text.length() > MAX_LENGTH ? line.text.substring(0, MAX_LENGTH) : line.text;
                    PeerLine out = new PeerLine(from.getUniqueID(), text);
                    for (EntityPlayer other : from.worldObj.playerEntities) {
                        if (other != from && other instanceof EntityPlayerMP mp && other.getDistanceSqToEntity(from) <= RANGE * RANGE) {
                            NET.sendTo(out, mp);
                        }
                    }
                });
                return null;
            }
        }
    }

    /** Server to client: the line a nearby player is on (empty = they stopped). */
    public static final class PeerLine implements IMessage {
        UUID player = new UUID(0, 0);
        String text = "";

        public PeerLine() {}

        PeerLine(UUID player, String text) {
            this.player = player;
            this.text = text;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            player = new UUID(buf.readLong(), buf.readLong());
            text = ByteBufUtils.readUTF8String(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(player.getMostSignificantBits());
            buf.writeLong(player.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, text);
        }

        public static final class Handler implements IMessageHandler<PeerLine, IMessage> {
            @Override
            public IMessage onMessage(PeerLine line, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
                Minecraft.getMinecraft().addScheduledTask(() -> PeerLyrics.receive(line.player, line.text));
                return null;
            }
        }
    }

    /** Server to client, on join: "I have Mc'Flow, you can share your lines". */
    public static final class Hello implements IMessage {
        public Hello() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static final class Handler implements IMessageHandler<Hello, IMessage> {
            @Override
            public IMessage onMessage(Hello hello, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
                serverHasMod = true;
                return null;
            }
        }
    }
}
