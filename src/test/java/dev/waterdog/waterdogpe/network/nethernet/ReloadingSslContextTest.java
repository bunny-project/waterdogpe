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

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReloadingSslContextTest {

    private static final String A = "CN=a.nethernet.test";
    private static final String B = "CN=b.nethernet.test";

    @TempDir
    Path dir;

    private Path certificate;
    private Path key;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @BeforeEach
    void setUp() {
        this.certificate = this.dir.resolve("fullchain.pem");
        this.key = this.dir.resolve("privkey.pem");
    }

    @Test
    void servesThePairItWasBuiltWith() throws Exception {
        ReloadingSslContext context = this.load("a");

        assertEquals(A, handshake(context));
        assertEquals(notAfter("a"), context.notAfter());
        assertFalse(context.reloadIfChanged(), "unchanged files are not read again");
    }

    @Test
    void swapsInASettledRenewal() throws Exception {
        ReloadingSslContext context = this.load("a");

        this.install("b", this.now.get() - 2 * ReloadingSslContext.SETTLE_MILLIS);

        assertTrue(context.reloadIfChanged());
        assertEquals(B, handshake(context));
        assertEquals(notAfter("b"), context.notAfter());
        assertFalse(context.reloadIfChanged(), "the new pair is read once");
    }

    @Test
    void waitsUntilBothFilesSettle() throws Exception {
        ReloadingSslContext context = this.load("a");

        this.install("b", this.now.get());

        assertFalse(context.reloadIfChanged(), "a pair that was just written is left for the next round");
        assertEquals(A, handshake(context));

        this.now.addAndGet(ReloadingSslContext.SETTLE_MILLIS);
        assertTrue(context.reloadIfChanged());
        assertEquals(B, handshake(context));
    }

    @Test
    void keepsServingWhenTheNewPairIsBroken() throws Exception {
        ReloadingSslContext context = this.load("a");

        Files.writeString(this.certificate, "not a certificate", StandardCharsets.US_ASCII);
        Files.setLastModifiedTime(this.certificate, FileTime.fromMillis(this.now.get() - 2 * ReloadingSslContext.SETTLE_MILLIS));

        assertFalse(context.reloadIfChanged());
        assertEquals(A, handshake(context));
        assertEquals(notAfter("a"), context.notAfter());
        assertFalse(context.reloadIfChanged(), "a broken pair is not retried until it changes");

        this.install("b", this.now.get() - ReloadingSslContext.SETTLE_MILLIS - 1);
        assertTrue(context.reloadIfChanged());
        assertEquals(B, handshake(context));
    }

    @Test
    void refusesMissingFilesLikeTheSignalingBuilder() {
        assertThrows(javax.net.ssl.SSLException.class,
                () -> ReloadingSslContext.pem(this.certificate, this.key, null, this.now::get));
    }

    private ReloadingSslContext load(String pair) throws Exception {
        this.install(pair, this.now.get() - 10 * ReloadingSslContext.SETTLE_MILLIS);
        return ReloadingSslContext.pem(this.certificate, this.key, null, this.now::get);
    }

    /** Writes the key and then the chain, as acme.sh does, both marked as modified at the given time. */
    private void install(String pair, long modified) throws Exception {
        copy(pair + "-key.pem", this.key);
        copy(pair + "-cert.pem", this.certificate);
        Files.setLastModifiedTime(this.key, FileTime.fromMillis(modified));
        Files.setLastModifiedTime(this.certificate, FileTime.fromMillis(modified));
    }

    private static void copy(String resource, Path target) throws Exception {
        try (InputStream in = ReloadingSslContextTest.class.getResourceAsStream("/nethernet-tls/" + resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Instant notAfter(String pair) throws Exception {
        try (InputStream in = ReloadingSslContextTest.class.getResourceAsStream("/nethernet-tls/" + pair + "-cert.pem")) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return certificate.getNotAfter().toInstant();
        }
    }

    /**
     * Runs a TLS handshake against a handler made the way the signaling server makes one for each
     * connection, and returns the subject of the certificate the client was shown.
     */
    private static String handshake(SslContext server) throws Exception {
        SslContext clientContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        SSLEngine client = clientContext.newEngine(ByteBufAllocator.DEFAULT, "nethernet.test", 443);
        SSLEngine serverEngine = server.newHandler(ByteBufAllocator.DEFAULT).engine();

        ByteBuffer clientToServer = ByteBuffer.allocate(64 * 1024);
        ByteBuffer serverToClient = ByteBuffer.allocate(64 * 1024);
        ByteBuffer clientApp = ByteBuffer.allocate(64 * 1024);
        ByteBuffer serverApp = ByteBuffer.allocate(64 * 1024);
        ByteBuffer empty = ByteBuffer.allocate(0);

        client.beginHandshake();
        serverEngine.beginHandshake();
        for (int round = 0; round < 200; round++) {
            client.wrap(empty, clientToServer);
            runTasks(client);
            serverEngine.wrap(empty, serverToClient);
            runTasks(serverEngine);

            clientToServer.flip();
            serverEngine.unwrap(clientToServer, serverApp);
            clientToServer.compact();
            runTasks(serverEngine);

            serverToClient.flip();
            client.unwrap(serverToClient, clientApp);
            serverToClient.compact();
            runTasks(client);

            if (finished(client) && finished(serverEngine)) {
                X509Certificate shown = (X509Certificate) client.getSession().getPeerCertificates()[0];
                return shown.getSubjectX500Principal().getName();
            }
        }
        throw new AssertionError("the TLS handshake did not finish");
    }

    private static boolean finished(SSLEngine engine) {
        SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
        return status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING || status == SSLEngineResult.HandshakeStatus.FINISHED;
    }

    private static void runTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }
}
