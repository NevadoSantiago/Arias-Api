package com.arias.users.notifications;

import com.arias.auth.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Token chico HMAC para el link "no quiero más recordatorios" del mail.
 *
 * <p>Formato del token: {@code base64url(userId).base64url(signature)}.
 * No expira — un mismo link sirve para siempre (no es una acción sensible,
 * a lo sumo desactiva sus propios mails).
 *
 * <p>Reusa el secret del JWT (`arias.jwt.secret`).
 */
@Component
@RequiredArgsConstructor
public class ReminderUnsubscribeToken {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final JwtProperties jwtProps;

    /** Genera un token "{userId}.{sig}" base64url-safe. */
    public String generate(Long userId) {
        String userIdEnc = ENC.encodeToString(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
        String sig = ENC.encodeToString(sign(userIdEnc));
        return userIdEnc + "." + sig;
    }

    /** Verifica el token y devuelve el userId si la firma matchea. */
    public Optional<Long> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) return Optional.empty();

        String userIdEnc = token.substring(0, dot);
        String sigEnc = token.substring(dot + 1);

        byte[] expected = sign(userIdEnc);
        byte[] given;
        try {
            given = DEC.decode(sigEnc);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!constantTimeEquals(expected, given)) return Optional.empty();

        try {
            String raw = new String(DEC.decode(userIdEnc), StandardCharsets.UTF_8);
            return Optional.of(Long.parseLong(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(jwtProps.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el token de unsubscribe", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
