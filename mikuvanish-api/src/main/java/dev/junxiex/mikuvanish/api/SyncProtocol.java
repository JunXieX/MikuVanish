package dev.junxiex.mikuvanish.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * 代理端与后端之间的 plugin messaging 二进制协议。
 *
 * <p>所有消息首字节为操作码，次字节为协议版本，后续字段使用 {@link DataOutputStream} 大端编码。</p>
 */
public final class SyncProtocol {

    /** 通道名（Velocity 与 Bukkit 两端注册同一通道）。 */
    public static final String CHANNEL = "mikuvanish:sync";

    /** 协议版本，置于每条消息第二字节。字段变更时递增，两端不一致会明确报错而非误读。 */
    public static final byte PROTOCOL_VERSION = 2;

    // 操作码
    /** 后端 -> 代理：上线握手，代理回推全量隐身快照（仅隐身玩家）。 */
    public static final byte OP_HELLO = 0x01;
    /** 双向：单个玩家隐身状态变更（后端本地切换后上报代理；代理权威盖写后广播各后端）。 */
    public static final byte OP_STATE_UPDATE = 0x02;
    /** 代理 -> 后端：全量快照推送完毕。 */
    public static final byte OP_SYNC_COMPLETE = 0x03;

    private SyncProtocol() {
    }

    /** 解码后的消息。 */
    public record Message(byte op, String serverId, VanishState state) {

        public static Message hello(String serverId) {
            return new Message(OP_HELLO, serverId, null);
        }

        public static Message stateUpdate(VanishState state) {
            return new Message(OP_STATE_UPDATE, null, state);
        }

        public static Message syncComplete(String serverId) {
            return new Message(OP_SYNC_COMPLETE, serverId, null);
        }
    }

    public static byte[] encode(Message msg) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(msg.op());
            out.writeByte(PROTOCOL_VERSION);
            switch (msg.op()) {
                case OP_HELLO, OP_SYNC_COMPLETE -> out.writeUTF(nullSafe(msg.serverId()));
                case OP_STATE_UPDATE -> {
                    VanishState s = msg.state();
                    out.writeUTF(s.uuid().toString());
                    out.writeUTF(s.username());
                    out.writeBoolean(s.vanished());
                    out.writeUTF(s.serverId());
                    out.writeLong(s.updatedAt());
                }
                default -> throw new IllegalArgumentException("unknown op: " + msg.op());
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Message decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte op = in.readByte();
            byte version = in.readByte();
            if (version != PROTOCOL_VERSION) {
                throw new IOException("protocol version mismatch: " + version);
            }
            switch (op) {
                case OP_HELLO:
                case OP_SYNC_COMPLETE:
                    return new Message(op, in.readUTF(), null);
                case OP_STATE_UPDATE: {
                    UUID uuid = UUID.fromString(in.readUTF());
                    String username = in.readUTF();
                    boolean vanished = in.readBoolean();
                    String serverId = in.readUTF();
                    long updatedAt = in.readLong();
                    return new Message(op, null, new VanishState(uuid, username, vanished, serverId, updatedAt));
                }
                default:
                    throw new IOException("unknown op: " + op);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
