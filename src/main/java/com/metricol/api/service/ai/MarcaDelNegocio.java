package com.metricol.api.service.ai;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.metricol.api.entity.BrandProfile;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.enums.TonoDeMarca;

/**
 * Lo que la IA sabe de la marca, y solo eso.
 *
 * <p>Es una copia y no el {@link BrandProfile} de la entidad por la misma razón
 * que {@code Redactor.Negocio}: la capa de IA recibe lo que el prompt va a leer.
 *
 * <p>Los datos de contacto existen para los TEXTOS (un caption que invita a
 * escribir por WhatsApp). Nunca van a la imagen: los modelos de imagen deforman
 * los números y una dirección mal escrita en una pieza publicada es peor que
 * ninguna.
 */
public record MarcaDelNegocio(
        String queVende,
        String publico,
        List<TonoDeMarca> tono,
        String evitar,
        String whatsapp,
        String web,
        String direccion,
        /** Cómo trabaja ({@link RasgoDelNegocio}); nulo = no se sabe todavía. */
        Set<RasgoDelNegocio> rasgos) {

    public static final MarcaDelNegocio VACIA = new MarcaDelNegocio(null, null, List.of(), null, null, null, null);

    /** Sin rasgos: lo de antes del perfil del negocio. */
    public MarcaDelNegocio(String queVende, String publico, List<TonoDeMarca> tono, String evitar, String whatsapp,
            String web, String direccion) {
        this(queVende, publico, tono, evitar, whatsapp, web, direccion, null);
    }

    /** La marca de un espacio con sus rasgos: lo que deben recibir todos los prompts. */
    public static MarcaDelNegocio delEspacio(Workspace w) {
        MarcaDelNegocio m = de(w.getBrandProfile());
        return new MarcaDelNegocio(m.queVende, m.publico, m.tono, m.evitar, m.whatsapp, m.web, m.direccion,
                w.rasgos());
    }

    public boolean tiene(RasgoDelNegocio rasgo) {
        return rasgos != null && rasgos.contains(rasgo);
    }

    /** Una línea por rasgo con lo que cambia al escribir o revisar. Vacío si no hay. */
    public String comoTrabajaEs() {
        if (rasgos == null || rasgos.isEmpty()) {
            return "";
        }
        return rasgos.stream().map(r -> r.instruccion).collect(Collectors.joining(" "));
    }

    public static MarcaDelNegocio de(BrandProfile perfil) {
        if (perfil == null) {
            return VACIA;
        }
        List<TonoDeMarca> tonos = perfil.tono() == null ? List.of() : perfil.tono().stream()
                .map(TonoDeMarca::de).flatMap(java.util.Optional::stream).limit(TonoDeMarca.MAXIMO).toList();
        return new MarcaDelNegocio(perfil.queVende(), perfil.publico(), tonos, perfil.evitar(), perfil.whatsapp(),
                perfil.web(), perfil.direccion());
    }

    public boolean hayContacto() {
        return hay(whatsapp) || hay(web) || hay(direccion);
    }

    /** «cercano y amable, divertido y con humor». Vacío si no eligió. */
    public String personalidadEs() {
        return tono == null ? "" : tono.stream().map(TonoDeMarca::es).collect(Collectors.joining(", "));
    }

    /** «warm and friendly, playful and funny». */
    public String personalidadEn() {
        return tono == null ? "" : tono.stream().map(TonoDeMarca::en).collect(Collectors.joining(", "));
    }

    /** «WhatsApp +52…, sitio web https://…, dirección …», solo lo que hay. */
    public String contactoEs() {
        java.util.List<String> partes = new java.util.ArrayList<>();
        if (hay(whatsapp)) {
            partes.add("WhatsApp " + whatsapp.strip());
        }
        if (hay(web)) {
            partes.add("sitio web " + web.strip());
        }
        if (hay(direccion)) {
            partes.add("direccion " + direccion.strip());
        }
        return String.join(", ", partes);
    }

    public static boolean hay(String s) {
        return s != null && !s.isBlank();
    }
}
