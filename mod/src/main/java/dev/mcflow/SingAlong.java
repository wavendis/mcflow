package dev.mcflow;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Multiplayer sing-along, the part that also runs on servers: a player shares the lyric line they're on, and the
 * server passes it on to everyone nearby, who see it above that player's head ({@link PeerLyrics}).
 * Both channels are optional, so players and servers without Mc'Flow connect as normal.
 */
public final class SingAlong {
    /** Lines go to players within this many blocks of the singer. */
    private static final double RANGE = 64;
    private static final int MAX_LENGTH = 200;

    private SingAlong() {}

    /** Client to server: "this is the line I'm on now" (empty = nothing playing). */
    public record ShareLine(String text) implements CustomPacketPayload {
        public static final Type<ShareLine> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcflow", "share_line"));
        public static final StreamCodec<ByteBuf, ShareLine> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_LENGTH), ShareLine::text, ShareLine::new);

        @Override
        public Type<ShareLine> type() {
            return TYPE;
        }
    }

    /** Server to client: the line a nearby player is on (empty = they stopped). */
    public record PeerLine(UUID player, String text) implements CustomPacketPayload {
        public static final Type<PeerLine> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcflow", "peer_line"));
        public static final StreamCodec<ByteBuf, PeerLine> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PeerLine::player, ByteBufCodecs.stringUtf8(MAX_LENGTH), PeerLine::text, PeerLine::new);

        @Override
        public Type<PeerLine> type() {
            return TYPE;
        }
    }

    /** Server side: passes a player's line on to everyone near them in the same world (not back to them). */
    public static void relay(ServerPlayer from, ShareLine line, BiConsumer<ServerPlayer, PeerLine> send) {
        String text = line.text().length() > MAX_LENGTH ? line.text().substring(0, MAX_LENGTH) : line.text();
        PeerLine out = new PeerLine(from.getUUID(), text);
        for (ServerPlayer other : from.level().players()) {
            if (other != from && other.distanceToSqr(from) <= RANGE * RANGE) send.accept(other, out);
        }
    }
}
