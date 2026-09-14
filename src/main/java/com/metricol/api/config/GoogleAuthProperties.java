package com.metricol.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Lo que hace falta para aceptar un inicio de sesión con Google.
 *
 * <p><b>El client id que va aquí es el del cliente WEB, no el de Android.</b>
 * Es la confusión más común de este flujo y da un error que no explica nada.
 * El cliente de Android no se nombra en ningún lado: Google identifica al APK
 * por su paquete y su huella SHA-1, y con eso decide si le entrega un token.
 * Pero el token que entrega viene dirigido al cliente web —es su
 * {@code audience}— porque es el que representa "el servidor de esta app".
 * Validar contra el id de Android rechazaría todos los tokens buenos.
 *
 * <p>El mismo valor va en la app como {@code serverClientId}: es lo que le
 * dice a Google para quién tiene que emitir el token.
 *
 * <p>Vacío = inicio de sesión con Google apagado. El resto de la aplicación
 * funciona igual, y entrar con correo y contraseña sigue disponible.
 */
@Configuration
@ConfigurationProperties(prefix = "google.oauth")
@Getter
@Setter
public class GoogleAuthProperties {

    /** El client id del cliente WEB. Ver la nota de la clase. */
    private String clientId;

    /**
     * Quién dice Google que emitió el token. Se comprueba además de la firma:
     * una firma válida de otro emisor seguiría siendo un token ajeno.
     */
    private String issuer = "https://accounts.google.com";

    /** De dónde se bajan las llaves públicas con las que Google firma. */
    private String jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }
}
