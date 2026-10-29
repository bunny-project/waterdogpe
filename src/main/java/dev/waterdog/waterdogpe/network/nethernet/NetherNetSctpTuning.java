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

import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.netty.channel.nethernet.NetherNetNative;
import tel.schich.libdatachannel.LibDataChannel;
import tel.schich.libdatachannel.SctpSettings;

import java.util.Optional;
import java.util.function.Function;

/**
 * SCTP timers of every NetherNet peer, which RakNet's {@code session_timeout} does not reach.
 * <p>
 * The network library fixes the minimum retransmit timeout at 400 ms and leaves the rest to
 * libdatachannel, so an association gives up after its default number of retransmits and
 * heartbeats, which is far shorter than the 30 seconds a RakNet client survives. A property that is
 * not set leaves the library's value alone, and with none set nothing is touched at all.
 * <ul>
 * <li>{@code waterdog.nethernetSctpMaxRetransmit}: retransmits of one chunk before the association
 * is declared dead, which ends the connection with {@code disconnect.timeout}.</li>
 * <li>{@code waterdog.nethernetSctpHeartbeat}: milliseconds between heartbeats on an idle path.</li>
 * <li>{@code waterdog.nethernetSctpMinRto}, {@code waterdog.nethernetSctpMaxRto} and
 * {@code waterdog.nethernetSctpInitialRto}: the retransmit timeout bounds and its starting value,
 * in milliseconds. The library keeps its 400 ms minimum unless {@code MinRto} says otherwise.</li>
 * </ul>
 * Every value is a positive integer, anything else is reported and ignored. The settings are
 * global to the native library, so they apply to downstream peers as well.
 */
@Log4j2
public final class NetherNetSctpTuning {

    /** What the network library sets when nothing else does. */
    static final int LIBRARY_MIN_RTO_MS = 400;

    static final String MAX_RETRANSMIT = "waterdog.nethernetSctpMaxRetransmit";
    static final String HEARTBEAT = "waterdog.nethernetSctpHeartbeat";
    static final String MIN_RTO = "waterdog.nethernetSctpMinRto";
    static final String MAX_RTO = "waterdog.nethernetSctpMaxRto";
    static final String INITIAL_RTO = "waterdog.nethernetSctpInitialRto";

    private NetherNetSctpTuning() {
    }

    /**
     * Loads the native library with the network library's own settings, then puts the overrides
     * from the system properties over them. Does nothing when none is set.
     * <p>
     * The network library applies its settings when it first loads the native code, so ours go
     * after that, or it would take them back.
     */
    public static void apply() {
        apply(System::getProperty);
    }

    static void apply(Function<String, String> properties) {
        Optional<SctpSettings> settings = settings(properties);
        if (settings.isEmpty()) {
            return;
        }
        NetherNetNative.initialize();
        LibDataChannel.setSctpSettings(settings.get());
        log.info("NetherNet SCTP settings set from system properties: {}", settings.get());
    }

    /**
     * The settings the properties ask for, empty when they ask for nothing.
     */
    static Optional<SctpSettings> settings(Function<String, String> properties) {
        Integer maxRetransmit = positive(properties, MAX_RETRANSMIT);
        Integer heartbeat = positive(properties, HEARTBEAT);
        Integer minRto = positive(properties, MIN_RTO);
        Integer maxRto = positive(properties, MAX_RTO);
        Integer initialRto = positive(properties, INITIAL_RTO);
        if (maxRetransmit == null && heartbeat == null && minRto == null && maxRto == null && initialRto == null) {
            return Optional.empty();
        }

        SctpSettings.Builder builder = SctpSettings.builder()
                .minRetransmitTimeoutMs(minRto != null ? minRto : LIBRARY_MIN_RTO_MS);
        if (maxRetransmit != null) {
            builder.maxRetransmitAttempts(maxRetransmit);
        }
        if (heartbeat != null) {
            builder.heartbeatIntervalMs(heartbeat);
        }
        if (maxRto != null) {
            builder.maxRetransmitTimeoutMs(maxRto);
        }
        if (initialRto != null) {
            builder.initialRetransmitTimeoutMs(initialRto);
        }
        return Optional.of(builder.build());
    }

    /**
     * The property as a positive integer, null when it is not set or not one.
     */
    private static Integer positive(Function<String, String> properties, String name) {
        String value = properties.apply(name);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        log.error("Invalid {} \"{}\", expected a positive integer, keeping the library's value", name, value);
        return null;
    }
}
