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
import io.netty.handler.ssl.ApplicationProtocolNegotiator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.log4j.Log4j2;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * The HTTPS context of built-in signaling, which picks up a renewed certificate without a restart.
 * <p>
 * The signaling server keeps the one context it was built with and hands it to every new
 * connection, so this context stands in for it and forwards to the context built from the
 * files last read. {@link #reloadIfChanged()} rebuilds it, the same way the signaling builder
 * does for a PEM pair, once the certificate or the key changed on disk. Connections that are
 * already open keep the context they started with.
 * <p>
 * A renewal writes the key and the certificate one after the other, so a pair is only read once
 * neither file changed for {@link #SETTLE_MILLIS}. A pair that fails to load leaves the current
 * one serving and is not retried until a file changes again.
 */
@Log4j2
public final class ReloadingSslContext extends SslContext {

    /** How long both files must be left alone before a changed pair is read. */
    public static final long SETTLE_MILLIS = 60_000L;

    private final Path certificate;
    private final Path privateKey;
    private final String password;
    private final LongSupplier clock;

    private volatile SslContext delegate;
    private volatile Instant notAfter;
    /** The files as they were when the serving context was read. */
    private FileState loaded;
    /** The files of the last pair that failed to load, not retried until they change. */
    private FileState failed;

    private ReloadingSslContext(Path certificate, Path privateKey, String password, LongSupplier clock) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.password = password;
        this.clock = clock;
    }

    /**
     * Reads a PEM certificate chain and private key, failing when they cannot be used, like the
     * signaling builder does.
     */
    public static ReloadingSslContext pem(Path certificate, Path privateKey, String password) throws SSLException {
        return pem(certificate, privateKey, password, System::currentTimeMillis);
    }

    static ReloadingSslContext pem(Path certificate, Path privateKey, String password, LongSupplier clock)
            throws SSLException {
        requireReadable(certificate, "certificate chain");
        requireReadable(privateKey, "private key");
        ReloadingSslContext context = new ReloadingSslContext(certificate, privateKey, password, clock);
        try {
            FileState state = context.state();
            context.delegate = context.build();
            context.notAfter = context.readNotAfter();
            context.loaded = state;
        } catch (IOException e) {
            throw new SSLException("Unable to read the NetherNet HTTPS certificate " + certificate, e);
        }
        return context;
    }

    /**
     * Swaps in the pair on disk when it changed since it was last read and has settled.
     *
     * @return whether a new pair now serves new connections
     */
    public synchronized boolean reloadIfChanged() {
        FileState state;
        try {
            state = this.state();
        } catch (IOException e) {
            log.warn("Unable to check the NetherNet HTTPS certificate files: {}", e.toString());
            return false;
        }
        if (state.equals(this.loaded) || state.equals(this.failed)) {
            return false;
        }
        if (this.clock.getAsLong() - state.newestModified() < SETTLE_MILLIS) {
            // Still being written, checked again on the next round
            return false;
        }
        SslContext next;
        Instant expiry;
        try {
            next = this.build();
            expiry = this.readNotAfter();
        } catch (Exception e) {
            this.failed = state;
            log.warn("The NetherNet HTTPS certificate on disk changed but could not be loaded, the previous one "
                    + "keeps serving: {}", e.toString());
            return false;
        }
        this.delegate = next;
        this.notAfter = expiry;
        this.loaded = state;
        this.failed = null;
        log.info("Reloaded the NetherNet HTTPS certificate, valid until {}", expiry);
        return true;
    }

    /** When the serving certificate, the first in the chain, expires. */
    public Instant notAfter() {
        return this.notAfter;
    }

    private SslContext build() throws SSLException {
        return SslContextBuilder.forServer(this.certificate.toFile(), this.privateKey.toFile(), this.password).build();
    }

    private Instant readNotAfter() throws IOException {
        try (InputStream in = Files.newInputStream(this.certificate)) {
            X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return leaf.getNotAfter().toInstant();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to read the certificate " + this.certificate, e);
        }
    }

    private FileState state() throws IOException {
        return new FileState(Files.getLastModifiedTime(this.certificate).toMillis(), Files.size(this.certificate),
                Files.getLastModifiedTime(this.privateKey).toMillis(), Files.size(this.privateKey));
    }

    private static void requireReadable(Path file, String what) throws SSLException {
        if (!Files.isReadable(file)) {
            throw new SSLException("The NetherNet HTTPS " + what + " " + file + " is missing or unreadable");
        }
    }

    private record FileState(long certificateModified, long certificateSize, long keyModified, long keySize) {
        long newestModified() {
            return Math.max(this.certificateModified, this.keyModified);
        }
    }

    // Everything below forwards to the context last read. The handler methods go through the
    // delegate's public ones, as its protected ones cannot be reached from here.

    @Override
    public boolean isClient() {
        return this.delegate.isClient();
    }

    @Override
    public List<String> cipherSuites() {
        return this.delegate.cipherSuites();
    }

    @Override
    public long sessionCacheSize() {
        return this.delegate.sessionCacheSize();
    }

    @Override
    public long sessionTimeout() {
        return this.delegate.sessionTimeout();
    }

    @Override
    @SuppressWarnings("deprecation")
    public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
        return this.delegate.applicationProtocolNegotiator();
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator alloc) {
        return this.delegate.newEngine(alloc);
    }

    @Override
    public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
        return this.delegate.newEngine(alloc, peerHost, peerPort);
    }

    @Override
    public SSLSessionContext sessionContext() {
        return this.delegate.sessionContext();
    }

    @Override
    protected SslHandler newHandler(ByteBufAllocator alloc, boolean startTls) {
        return this.delegate.newHandler(alloc);
    }

    @Override
    public SslHandler newHandler(ByteBufAllocator alloc, Executor delegatedTaskExecutor) {
        return this.delegate.newHandler(alloc, delegatedTaskExecutor);
    }

    @Override
    protected SslHandler newHandler(ByteBufAllocator alloc, boolean startTls, Executor executor) {
        return this.delegate.newHandler(alloc, executor);
    }

    @Override
    protected SslHandler newHandler(ByteBufAllocator alloc, String peerHost, int peerPort, boolean startTls) {
        return this.delegate.newHandler(alloc, peerHost, peerPort);
    }

    @Override
    public SslHandler newHandler(ByteBufAllocator alloc, String peerHost, int peerPort, Executor delegatedTaskExecutor) {
        return this.delegate.newHandler(alloc, peerHost, peerPort, delegatedTaskExecutor);
    }

    @Override
    protected SslHandler newHandler(ByteBufAllocator alloc, String peerHost, int peerPort, boolean startTls,
                                    Executor delegatedTaskExecutor) {
        return this.delegate.newHandler(alloc, peerHost, peerPort, delegatedTaskExecutor);
    }

    @Override
    public String toString() {
        return "ReloadingSslContext{" + this.certificate + ", valid until " + Objects.toString(this.notAfter) + "}";
    }
}
