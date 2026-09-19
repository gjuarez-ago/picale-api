package com.metricol.api.service.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Comprueba que un aviso viene de verdad de Stripe.
 *
 * <p>Sin esto, cualquiera podría mandar un "pagó" a la dirección del webhook y
 * regalarse licencias y créditos. Stripe firma cada aviso con el secreto del
 * endpoint: el encabezado {@code Stripe-Signature} trae la hora ({@code t=})
 * y una o más firmas ({@code v1=}) del texto {@code t + "." + cuerpo}.
 *
 * <p>Se compara en tiempo constante, y se rechaza lo demasiado viejo: un aviso
 * capturado y reenviado una semana después no sirve.
 */
public final class StripeWebhookVerifier {

    private StripeWebhookVerifier() {
    }

    /**
     * @param cuerpo los bytes EXACTOS que llegaron: reserializar el JSON cambiaría la firma
     * @return {@code true} si alguna firma coincide y la hora está dentro de la tolerancia
     */
    public static boolean valido(byte[] cuerpo, String encabezado, String secreto, int toleranciaSegundos, Instant ahora) {
        if (cuerpo == null || encabezado == null || encabezado.isBlank() || secreto == null || secreto.isBlank()) {
            return false;
        }

        long marca = -1;
        List<String> firmas = new ArrayList<>();
        for (String parte : encabezado.split(",")) {
            String[] par = parte.trim().split("=", 2);
            if (par.length != 2) {
                continue;
            }
            if (par[0].equals("t")) {
                try {
                    marca = Long.parseLong(par[1]);
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if (par[0].equals("v1")) {
                firmas.add(par[1]);
            }
        }
        if (marca < 0 || firmas.isEmpty()) {
            return false;
        }
        if (Math.abs(ahora.getEpochSecond() - marca) > toleranciaSegundos) {
            return false;
        }

        byte[] esperada = firmar(marca, cuerpo, secreto);
        for (String candidata : firmas) {
            try {
                if (MessageDigest.isEqual(esperada, HexFormat.of().parseHex(candidata))) {
                    return true;
                }
            } catch (IllegalArgumentException ex) {
                // No era hexadecimal: no coincide.
            }
        }
        return false;
    }

    /** La firma que Stripe calcularía. Pública para las pruebas. */
    public static byte[] firmar(long marca, byte[] cuerpo, String secreto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((marca + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(cuerpo);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular la firma.", ex);
        }
    }
}
