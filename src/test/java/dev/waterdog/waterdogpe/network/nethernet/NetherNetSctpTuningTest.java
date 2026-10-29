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

import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.SctpSettings;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetherNetSctpTuningTest {

    private static Optional<SctpSettings> settings(Map<String, String> properties) {
        return NetherNetSctpTuning.settings(properties::get);
    }

    private static int field(SctpSettings settings, String name) throws Exception {
        Field field = SctpSettings.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(settings);
    }

    @Test
    public void touchesNothingWithoutProperties() {
        assertTrue(settings(Map.of()).isEmpty());
    }

    @Test
    public void ignoresValuesThatAreNotPositiveIntegers() {
        assertTrue(settings(Map.of(
                NetherNetSctpTuning.MAX_RETRANSMIT, "many",
                NetherNetSctpTuning.HEARTBEAT, "0",
                NetherNetSctpTuning.MAX_RTO, "-5",
                NetherNetSctpTuning.INITIAL_RTO, "")).isEmpty());
    }

    @Test
    public void keepsTheNetworkLibrarysMinimumRtoWhenOnlyOthersAreSet() throws Exception {
        SctpSettings settings = settings(Map.of(NetherNetSctpTuning.MAX_RETRANSMIT, "20")).orElseThrow();

        assertEquals(20, field(settings, "maxRetransmitAttempts"));
        assertEquals(NetherNetSctpTuning.LIBRARY_MIN_RTO_MS, field(settings, "minRetransmitTimeoutMs"));
        // Left at the library default
        assertEquals(0, field(settings, "heartbeatIntervalMs"));
        assertEquals(0, field(settings, "maxRetransmitTimeoutMs"));
        assertEquals(0, field(settings, "initialRetransmitTimeoutMs"));
    }

    @Test
    public void appliesEveryValueThatIsSet() throws Exception {
        SctpSettings settings = settings(Map.of(
                NetherNetSctpTuning.MAX_RETRANSMIT, " 12 ",
                NetherNetSctpTuning.HEARTBEAT, "2000",
                NetherNetSctpTuning.MIN_RTO, "250",
                NetherNetSctpTuning.MAX_RTO, "5000",
                NetherNetSctpTuning.INITIAL_RTO, "800")).orElseThrow();

        assertEquals(12, field(settings, "maxRetransmitAttempts"));
        assertEquals(2000, field(settings, "heartbeatIntervalMs"));
        assertEquals(250, field(settings, "minRetransmitTimeoutMs"));
        assertEquals(5000, field(settings, "maxRetransmitTimeoutMs"));
        assertEquals(800, field(settings, "initialRetransmitTimeoutMs"));
    }

    @Test
    public void aBadValueDoesNotDropTheGoodOnes() throws Exception {
        SctpSettings settings = settings(Map.of(
                NetherNetSctpTuning.MAX_RETRANSMIT, "12",
                NetherNetSctpTuning.HEARTBEAT, "fast")).orElseThrow();

        assertEquals(12, field(settings, "maxRetransmitAttempts"));
        assertEquals(0, field(settings, "heartbeatIntervalMs"));
    }
}
