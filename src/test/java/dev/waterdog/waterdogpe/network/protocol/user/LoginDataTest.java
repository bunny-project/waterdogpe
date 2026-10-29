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

package dev.waterdog.waterdogpe.network.protocol.user;

import com.google.gson.JsonObject;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.SignedJWT;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login a player moves to another server with. A downstream refuses a token past its expiry, so a
 * player connected for longer than a day must not be sent the login signed when they joined.
 */
public class LoginDataTest {

    private static final long DAY = 24 * 3600;

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(Curve.P_384.toECParameterSpec());
        return generator.generateKeyPair();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String expiredJwt(KeyPair pair) {
        long joined = now() - DAY - 60;
        JsonObject claims = new JsonObject();
        claims.addProperty("iat", joined);
        claims.addProperty("exp", joined + DAY);
        claims.addProperty("iss", "self");
        return HandshakeUtils.encodeJWT(pair, claims).serialize();
    }

    private static LoginData.LoginDataBuilder player(KeyPair pair) {
        return LoginData.builder()
                .displayName("TestPlayer")
                .uuid(UUID.randomUUID())
                .xuid("123456")
                .minecraftId("TestMinecraftId")
                .keyPair(pair)
                .clientData(new JsonObject());
    }

    private static long expiry(LoginPacket packet) throws Exception {
        String jwt = packet.getAuthPayload() instanceof TokenPayload token
                ? token.getToken()
                : ((CertificateChainPayload) packet.getAuthPayload()).getChain().get(0);
        return SignedJWT.parse(jwt).getJWTClaimsSet().getExpirationTime().getTime() / 1000;
    }

    @Test
    void movingADayAfterJoiningSendsAFreshToken() throws Exception {
        KeyPair pair = keyPair();
        LoginPacket joined = new LoginPacket();
        joined.setAuthPayload(new TokenPayload(expiredJwt(pair), AuthType.SELF_SIGNED));
        LoginData data = player(pair)
                .protocol(ProtocolVersion.MINECRAFT_PE_1_26_60)
                .shouldSendCertificateChain(false)
                .loginPacket(joined)
                .build();

        assertTrue(expiry(data.getLoginPacket()) > now() + DAY - 60);
    }

    @Test
    void movingADayAfterJoiningSendsAFreshChain() throws Exception {
        KeyPair pair = keyPair();
        LoginPacket joined = new LoginPacket();
        joined.setAuthPayload(new CertificateChainPayload(Collections.singletonList(expiredJwt(pair)), AuthType.SELF_SIGNED));
        LoginData data = player(pair)
                .protocol(ProtocolVersion.MINECRAFT_PE_1_21_90)
                .shouldSendCertificateChain(true)
                .loginPacket(joined)
                .build();

        assertTrue(expiry(data.getLoginPacket()) > now() + DAY - 60);
    }
}
