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

package dev.waterdog.waterdogpe.utils.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lang.ini saved by an older jar lacks keys added later; those must fall back to the bundled text.
 */
public class LangConfigTest {

    @Test
    void fileOverridesBundledAndMissingKeysFallBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lang.ini");
        Files.writeString(file, "waterdog.downstream.transfer.failed=custom {%0}\r\n", StandardCharsets.UTF_8);

        LangConfig lang = new LangConfig(file.toFile());

        assertEquals("custom hub", lang.translateString("waterdog.downstream.transfer.failed", "hub"));
        String outdated = lang.translateString("waterdog.downstream.client.outdated", "1.26.50");
        assertNotEquals("waterdog.downstream.client.outdated", outdated);
        assertTrue(outdated.contains("1.26.50"));
    }
}
