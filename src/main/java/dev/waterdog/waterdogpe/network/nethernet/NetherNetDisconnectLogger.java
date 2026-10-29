/*
 * Copyright 2026 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.nethernet;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import tel.schich.libdatachannel.SctpStats;

import java.net.SocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Writes one line when the transport ends a NetherNet player connection, with the reason it gives
 * and the state of the SCTP association at that moment, to tell a path that went quiet from a peer
 * that left or a proxy that stalled right after a server switch.
 * <p>
 * Only closes the transport reports are logged. A player the proxy kicks or sends away never
 * raises the event. The statistics are read before the peer is closed, and are whatever the native
 * library still has once the association has failed, which may be nothing.
 */
public final class NetherNetDisconnectLogger extends ChannelInboundHandlerAdapter {
    public static final String NAME = "nethernet-disconnect-logger";

    private final Function<Channel, ProxiedPlayer> players;
    private final Function<Channel, SctpStats> stats;
    private final Consumer<String> sink;
    private final long openedAt = System.currentTimeMillis();
    private boolean logged;

    NetherNetDisconnectLogger(Function<Channel, ProxiedPlayer> players, Function<Channel, SctpStats> stats,
                              Consumer<String> sink) {
        this.players = players;
        this.stats = stats;
        this.sink = sink;
    }

    public static NetherNetDisconnectLogger of(ProxyServer proxy) {
        return new NetherNetDisconnectLogger(channel -> findPlayer(proxy, channel), NetherNetDisconnectLogger::sctpStats,
                line -> proxy.getLogger().info("{}", line));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof TransportDisconnectReason reason && !this.logged) {
            this.logged = true;
            try {
                this.sink.accept(this.describe(ctx.channel(), reason, System.currentTimeMillis()));
            } catch (RuntimeException | LinkageError e) {
                // A diagnostic must never get in the way of the close it describes
            }
        }
        super.userEventTriggered(ctx, event);
    }

    String describe(Channel channel, TransportDisconnectReason reason, long now) {
        ProxiedPlayer player = this.players.apply(channel);
        SocketAddress address = channel.remoteAddress();
        StringBuilder line = new StringBuilder("[").append(address);
        if (player != null) {
            line.append('|').append(player.getName());
        }
        line.append("] <-> NetherNet upstream ended by transport: reason=").append(reason.name())
                .append(" (").append(label(reason)).append(')')
                .append(" connected=").append(seconds(now - this.openedAt));
        if (player != null) {
            ServerInfo server = player.getServerInfo();
            line.append(" server=").append(server == null ? "none" : server.getServerName());
            long switched = player.getLastServerSwitchMillis();
            line.append(" sinceSwitch=").append(switched == 0 ? "never" : seconds(now - switched));
        }
        SctpStats sctp = this.stats.apply(channel);
        if (sctp == null) {
            line.append(" sctp=unavailable");
        } else {
            line.append(" sctp[rtt=").append(sctp.rtt().toMillis()).append("ms")
                    .append(" rto=").append(sctp.rto().toMillis()).append("ms")
                    .append(" cwnd=").append(sctp.congestionWindow())
                    .append(" peerRwnd=").append(sctp.peerReceiveWindow())
                    .append(" unacked=").append(sctp.unackedChunks())
                    .append(" pending=").append(sctp.pendingChunks())
                    .append(" dataTimeouts=").append(sctp.dataTimeouts()).append(']');
        }
        return line.toString();
    }

    /**
     * The native close reason each transport reason came from, which is what the line is for.
     */
    static String label(TransportDisconnectReason reason) {
        return switch (reason) {
            case TIMED_OUT -> "sctp_timeout";
            case CONNECTION_LOST -> "ice_failed";
            case CLOSED_BY_REMOTE_PEER -> "remote_shutdown";
            case ABORTED_BY_REMOTE_PEER -> "remote_abort";
            case TRANSPORT_FAILED -> "transport_failed";
            default -> "other";
        };
    }

    private static String seconds(long millis) {
        return Math.max(0, millis) / 1000 + "s";
    }

    private static SctpStats sctpStats(Channel channel) {
        if (!(channel instanceof NetherNetChannel nether)) {
            return null;
        }
        try {
            return nether.sctpStats();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static ProxiedPlayer findPlayer(ProxyServer proxy, Channel channel) {
        for (ProxiedPlayer player : proxy.getPlayers().values()) {
            if (player.getConnection() != null && player.getConnection().getPeer().getChannel() == channel) {
                return player;
            }
        }
        return null;
    }
}
