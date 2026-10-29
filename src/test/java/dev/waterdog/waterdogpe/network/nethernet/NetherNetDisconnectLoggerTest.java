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

import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.SctpStats;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NetherNetDisconnectLoggerTest {

    private final List<String> lines = new ArrayList<>();
    private final List<Object> downstreamEvents = new ArrayList<>();

    private EmbeddedChannel channel(Function<Channel, ProxiedPlayer> players, Function<Channel, SctpStats> stats) {
        return new EmbeddedChannel(
                new NetherNetDisconnectLogger(players, stats, this.lines::add),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                        NetherNetDisconnectLoggerTest.this.downstreamEvents.add(event);
                    }
                });
    }

    private static SctpStats stats() throws Exception {
        Method create = SctpStats.class.getDeclaredMethod("create", int.class, int.class, long.class, long.class,
                long.class, long.class, long.class);
        create.setAccessible(true);
        return (SctpStats) create.invoke(null, 35, 400, 4380L, 65535L, 3L, 7L, 2L);
    }

    @Test
    public void logsTheReasonStatsAndTimeSinceTheSwitch() throws Exception {
        ServerInfo server = mock(ServerInfo.class);
        when(server.getServerName()).thenReturn("island1");
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn("Steve");
        when(player.getServerInfo()).thenReturn(server);
        when(player.getLastServerSwitchMillis()).thenReturn(System.currentTimeMillis() - 42_000);
        SctpStats stats = stats();

        EmbeddedChannel channel = channel(c -> player, c -> stats);
        channel.pipeline().fireUserEventTriggered(TransportDisconnectReason.TIMED_OUT);

        assertEquals(1, this.lines.size());
        String line = this.lines.get(0);
        assertTrue(line.contains("|Steve]"), line);
        assertTrue(line.contains("reason=TIMED_OUT (sctp_timeout)"), line);
        assertTrue(line.contains("server=island1"), line);
        assertTrue(line.matches(".*sinceSwitch=4[2-3]s.*"), line);
        assertTrue(line.contains("sctp[rtt=35ms rto=400ms cwnd=4380 peerRwnd=65535 unacked=3 pending=7 dataTimeouts=2]"), line);
    }

    @Test
    public void passesTheEventOnAndLogsOnlyOnce() {
        EmbeddedChannel channel = channel(c -> null, c -> null);

        channel.pipeline().fireUserEventTriggered(TransportDisconnectReason.CONNECTION_LOST);
        channel.pipeline().fireUserEventTriggered(TransportDisconnectReason.TIMED_OUT);

        assertEquals(1, this.lines.size());
        assertTrue(this.lines.get(0).contains("reason=CONNECTION_LOST (ice_failed)"), this.lines.get(0));
        assertEquals(List.of(TransportDisconnectReason.CONNECTION_LOST, TransportDisconnectReason.TIMED_OUT),
                this.downstreamEvents);
    }

    @Test
    public void copesWithoutPlayerOrStats() {
        EmbeddedChannel channel = channel(c -> null, c -> null);

        channel.pipeline().fireUserEventTriggered(TransportDisconnectReason.ABORTED_BY_REMOTE_PEER);

        String line = this.lines.get(0);
        assertTrue(line.contains("(remote_abort)"), line);
        assertTrue(line.contains("sctp=unavailable"), line);
        assertFalse(line.contains("server="), line);
    }

    @Test
    public void ignoresOtherEvents() {
        EmbeddedChannel channel = channel(c -> null, c -> null);

        channel.pipeline().fireUserEventTriggered("something else");

        assertTrue(this.lines.isEmpty());
        assertEquals(List.of("something else"), this.downstreamEvents);
    }

    @Test
    public void aFailingLookupDoesNotLoseTheEvent() {
        EmbeddedChannel channel = channel(c -> {
            throw new IllegalStateException("boom");
        }, c -> null);

        channel.pipeline().fireUserEventTriggered(TransportDisconnectReason.TIMED_OUT);

        assertTrue(this.lines.isEmpty());
        assertEquals(List.of(TransportDisconnectReason.TIMED_OUT), this.downstreamEvents);
    }
}
