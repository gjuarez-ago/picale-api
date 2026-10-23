package com.metricol.api.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las groserías que no pueden salir de Pícale: ni en lo que contesta Chispa ni en lo que
 * se publica a nombre de un negocio.
 *
 * <p>Las reglas del prompt van primero y casi siempre bastan. Esto es la red de debajo,
 * determinista: un modelo que repite lo que la persona dictó ("está bien chingón") o que
 * se contagia de un mensaje molesto no debe poder publicarlo. Español de México, más
 * algunas en inglés.
 *
 * <p>Se equivoca hacia lo seguro, pero evita los falsos positivos conocidos: "cono" (sin
 * eñe), "recoger", "computadora", "pinche de cocina".
 */
public final class LenguajeCuidado {

    private LenguajeCuidado() {
    }

    private static final Pattern GROSERIA = Pattern.compile(
            "\\b(?:"
                    + "pendej\\w*"
                    + "|ching(?:ar|ada|adas|ado|ados|adera|aderas|on|ón|ona|ones|onas|o|os|a|as|ue|uen|aste|ó|azo|azos|uiste)"
                    + "|put[ao]s?|putaz\\w*|putiz\\w*|putear\\w*"
                    + "|verg(?:a|as|uero|uera|azo|azos)"
                    + "|cul[eo]r[oa]s?|culo|culos"
                    + "|cabr[oó]n(?:a|es|as)?|cabronad\\w*"
                    + "|mierd\\w*"
                    + "|jot[oa]s?"
                    + "|maric[oó]n\\w*|marica|maricas"
                    + "|pinche(?!\\s+de\\s+cocina)\\w*"
                    + "|mamadas?|mam[oó]n(?:a|es|as)?"
                    + "|huev[oó]n\\w*|g[uü]ev[oó]n\\w*"
                    + "|carajo|coño"
                    + "|cog(?:er|e|es|io|ió|iendo|ida|idas|ido|idos)"
                    + "|hdp|ptm|ctm|alv|vrg|nmms"
                    + "|fuck\\w*|shit\\w*|bitch\\w*"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Las groserías que trae el texto, tal como aparecen. Vacío si no hay. */
    public static List<String> groserias(String texto) {
        List<String> encontradas = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return encontradas;
        }
        Matcher m = GROSERIA.matcher(texto);
        while (m.find()) {
            encontradas.add(m.group());
        }
        return encontradas;
    }

    public static boolean tieneGroserias(String texto) {
        return texto != null && GROSERIA.matcher(texto).find();
    }

    /**
     * Tapa cada grosería dejando su primera letra ("p*****"): se entiende que algo se quitó
     * a propósito y no cambia el resto del mensaje. Es el último recurso, cuando reescribir
     * no fue posible.
     */
    public static String cubrir(String texto) {
        if (texto == null || texto.isBlank()) {
            return texto;
        }
        Matcher m = GROSERIA.matcher(texto);
        StringBuilder salida = new StringBuilder();
        while (m.find()) {
            String palabra = m.group();
            m.appendReplacement(salida, Matcher.quoteReplacement(
                    palabra.charAt(0) + "*".repeat(Math.max(1, palabra.length() - 1))));
        }
        m.appendTail(salida);
        return salida.toString();
    }
}
