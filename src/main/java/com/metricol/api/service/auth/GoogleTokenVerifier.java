package com.metricol.api.service.auth;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import com.metricol.api.config.GoogleAuthProperties;

/**
 * Comprueba que un ID token venga de verdad de Google y sea para esta app.
 *
 * <p>Son cuatro cosas y las cuatro importan: que la firma cuadre con las
 * llaves públicas de Google, que no esté vencido, que lo haya emitido Google
 * y que vaya dirigido a nuestro cliente. Saltarse la última es el fallo
 * clásico: un token con firma perfecta, emitido por Google, pero para OTRA
 * aplicación — cualquiera con una app en Google podría entrar con él.
 *
 * <p>El decodificador se arma una sola vez y él se encarga de bajar y
 * refrescar las llaves de Google, que rotan cada pocos días. Pedirlas en cada
 * inicio de sesión sería una llamada de red extra por login.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    private final GoogleAuthProperties props;

    /**
     * Perezoso a propósito: sin Google configurado no se arma, y así el
     * arranque no depende de poder alcanzar los servidores de Google.
     */
    private volatile NimbusJwtDecoder decoder;

    public GoogleTokenVerifier(GoogleAuthProperties props) {
        this.props = props;
    }

    /** Los datos de la persona, ya verificados. */
    public record Identidad(String subject, String email, String nombre, boolean emailVerificado) {
    }

    public Identidad verificar(String idToken) {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "Falta configurar GOOGLE_OAUTH_CLIENT_ID en el servidor (el client id del cliente WEB).");
        }

        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException ex) {
            // El motivo se registra pero no se devuelve: a quien intenta
            // entrar con un token ajeno no se le explica por qué falló.
            log.warn("ID token de Google rechazado: {}", ex.getMessage());
            throw new IllegalArgumentException("No se pudo validar tu cuenta de Google. Intenta de nuevo.");
        }

        // El emisor, además de la firma: una firma válida de otro emisor
        // seguiría siendo un token que no es nuestro.
        String emisor = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (!props.getIssuer().equals(emisor) && !"accounts.google.com".equals(emisor)) {
            log.warn("ID token con emisor inesperado: {}", emisor);
            throw new IllegalArgumentException("No se pudo validar tu cuenta de Google.");
        }

        // La audiencia: el token tiene que venir dirigido a NUESTRO cliente.
        List<String> audiencia = jwt.getAudience();
        if (audiencia == null || !audiencia.contains(props.getClientId())) {
            log.warn("ID token dirigido a otro cliente: {}", audiencia);
            throw new IllegalArgumentException("Esa cuenta de Google no es para esta aplicación.");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Tu cuenta de Google no comparte un correo.");
        }

        return new Identidad(
                jwt.getSubject(),
                email.toLowerCase().trim(),
                jwt.getClaimAsString("name"),
                Boolean.TRUE.equals(jwt.getClaim("email_verified")));
    }

    /**
     * Doble comprobación al armarlo: dos inicios de sesión simultáneos en el
     * primer arranque construirían dos decodificadores, y cada uno se bajaría
     * las llaves de Google por su cuenta.
     */
    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder actual = decoder;
        if (actual != null) {
            return actual;
        }
        synchronized (this) {
            if (decoder == null) {
                decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
            }
            return decoder;
        }
    }
}
