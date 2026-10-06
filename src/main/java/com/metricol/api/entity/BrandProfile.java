package com.metricol.api.entity;

import java.util.List;

/**
 * Lo que la IA necesita saber de un negocio además de su giro, ciudad y
 * descripción: qué vende, a quién le habla, cómo suena, qué evitar y cómo
 * contactarlo.
 *
 * <p>Va como UN documento por espacio de trabajo (una columna con JSON) y no
 * como una columna por dato: la lista va a crecer —estilo visual, memoria— y
 * cada campo nuevo no debería ser una migración.
 *
 * <p>Los textos son de la persona y terminan dentro de un prompt, así que
 * {@code BrandService} los limpia y los acota antes de guardarlos. Todo es
 * opcional; un espacio sin nada llena esto con nulos.
 *
 * @param queVende   qué vende o qué servicios destaca
 * @param publico    a quién le habla
 * @param tono       códigos de {@link com.metricol.api.enums.TonoDeMarca}, hasta tres
 * @param evitar     lo que nunca debe decir o hacer ("no hablar de precios")
 * @param whatsapp   con lada de país: +5219991234567
 * @param web        con https://
 * @param direccion  dónde está
 * @param historia   por qué existe el negocio, quién está detrás
 * @param valores    lo que lo distingue ("puntualidad, trabajo bien hecho")
 * @param frases     su eslogan y sus frases, una por línea
 * @param pilares    códigos de {@link com.metricol.api.enums.PilarDeContenido}: de qué quiere hablar
 */
public record BrandProfile(
        String queVende,
        String publico,
        List<String> tono,
        String evitar,
        String whatsapp,
        String web,
        String direccion,
        String historia,
        String valores,
        String frases,
        List<String> pilares) {

    public static final BrandProfile VACIO = new BrandProfile(null, null, List.of(), null, null, null, null);

    /** Sin la voz de la marca (historia, valores, frases, pilares): lo de antes. */
    public BrandProfile(String queVende, String publico, List<String> tono, String evitar, String whatsapp, String web,
            String direccion) {
        this(queVende, publico, tono, evitar, whatsapp, web, direccion, null, null, null, null);
    }

    /** Sin un solo dato: se guarda como nulo en vez de un documento lleno de nulos. */
    public boolean vacio() {
        return vacio(queVende) && vacio(publico) && (tono == null || tono.isEmpty()) && vacio(evitar)
                && vacio(whatsapp) && vacio(web) && vacio(direccion) && vacio(historia) && vacio(valores)
                && vacio(frases) && (pilares == null || pilares.isEmpty());
    }

    /** Con al menos una forma de contactarlo. */
    public boolean hayContacto() {
        return !vacio(whatsapp) || !vacio(web) || !vacio(direccion);
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
