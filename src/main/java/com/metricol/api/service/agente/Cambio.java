package com.metricol.api.service.agente;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Lo que la persona le pide cambiar a una propuesta ("¿Le cambiamos algo?").
 *
 * <p>Todo el texto viaja a quien escribe (y al diseño, si lo hay). Además, unas
 * pocas frases muy claras cambian la decisión misma: quitar o poner el logo, y
 * pedir o quitar el diseño. Se reconocen con palabras y no con IA a propósito:
 * "sin logo" no admite dos lecturas, y así el cambio sale igual cada vez.
 *
 * @param texto  lo que escribió, tal cual
 * @param logo   {@code true} = con logo, {@code false} = sin, {@code null} = como decida el agente
 * @param diseno {@code true} = con diseño, {@code false} = tal cual, {@code null} = como decida el agente
 */
public record Cambio(String texto, Boolean logo, Boolean diseno) {

    public static Cambio de(String texto) {
        String t = texto == null ? "" : texto.strip();
        String n = normal(t);
        Boolean logo = null;
        if (contiene(n, "sin logo", "quita el logo", "quitale el logo", "no lleva logo", "sin el logo")) {
            logo = false;
        } else if (contiene(n, "con logo", "pon el logo", "ponle el logo", "agrega el logo", "con el logo")) {
            logo = true;
        }
        Boolean diseno = null;
        if (contiene(n, "sin diseno", "tal cual", "sin disenar", "quita el diseno", "la foto sola", "solo la foto")) {
            diseno = false;
        } else if (contiene(n, "disenala", "disenalo", "hazle diseno", "con diseno", "haz un diseno", "disena")) {
            diseno = true;
        }
        return new Cambio(t, logo, diseno);
    }

    public boolean vacio() {
        return texto.isBlank();
    }

    /** En minúsculas y sin acentos, para comparar sin depender de cómo se escribió. */
    private static String normal(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static boolean contiene(String texto, String... frases) {
        for (String f : frases) {
            if (texto.contains(f)) {
                return true;
            }
        }
        return false;
    }
}
