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

    /** What a message carries: the lyric line you're on, a word for your trail, or a beat of your glow ("rrggbb strength"). */
    public static final int LINE = 0, TRAIL = 1, BEAT = 2;

    private SingAlong() {}

    /** Client to server: {@link #LINE} "this is the line I'm on now" (empty = nothing playing), a {@link #TRAIL} word or a {@link #BEAT}. */
    public record ShareLine(int kind, String text) implements CustomPacketPayload {
        // "_v2" since the kind was added: servers with an older Mc'Flow just look like ones without it
        public static final Type<ShareLine> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcflow", "share_v2"));
        public static final StreamCodec<ByteBuf, ShareLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ShareLine::kind, ByteBufCodecs.stringUtf8(MAX_LENGTH), ShareLine::text, ShareLine::new);

        @Override
        public Type<ShareLine> type() {
            return TYPE;
        }
    }

    /** Server to client: what a nearby player shared (see {@link ShareLine}; an empty line = they stopped). */
    public record PeerLine(UUID player, int kind, String text) implements CustomPacketPayload {
        public static final Type<PeerLine> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mcflow", "peer_v2"));
        public static final StreamCodec<ByteBuf, PeerLine> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, PeerLine::player,
                ByteBufCodecs.VAR_INT, PeerLine::kind, ByteBufCodecs.stringUtf8(MAX_LENGTH), PeerLine::text, PeerLine::new);

        @Override
        public Type<PeerLine> type() {
            return TYPE;
        }
    }

    /** Server side: passes what a player shared on to everyone near them in the same world (not back to them). */
    public static void relay(ServerPlayer from, ShareLine line, BiConsumer<ServerPlayer, PeerLine> send) {
        String text = line.text().length() > MAX_LENGTH ? line.text().substring(0, MAX_LENGTH) : line.text();
        PeerLine out = new PeerLine(from.getUUID(), line.kind(), text);
        for (ServerPlayer other : from.level().players()) {
            if (other != from && other.distanceToSqr(from) <= RANGE * RANGE) send.accept(other, out);
        }
    }
}
