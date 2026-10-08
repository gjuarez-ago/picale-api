package com.metricol.api.service.imagenes;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lo que hay que saber antes de crear una imagen, y que la conversación va
 * llenando.
 *
 * <p>Es la pieza que hace que el chat no sea un juguete. El generador que ya
 * existe ({@code CampaignImageService}) no entiende conversaciones: necesita
 * datos concretos. Así que hablar no reemplaza esos datos, los <b>llena</b>.
 *
 * <p>Y como la ficha se enseña en pantalla mientras se llena, la persona ve lo
 * que el asistente entendió y lo corrige tocando, sin tener que explicarlo
 * otra vez con palabras. Esa es la diferencia entre un asistente que te
 * entiende y uno con el que peleas.
 *
 * <p>Todo es opcional menos lo que dice {@link #queFalta()}: crear a medias
 * gasta créditos para tirar el resultado.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FichaDeImagen(
        /** Qué se anuncia, en las palabras de la persona ("2x1 en tacos al pastor"). */
        String queSeAnuncia,

        /** PUBLICACION, HISTORIA o CARRUSEL. Lo dice la persona o lo propone el asistente. */
        String formato,

        /** A qué redes va. Si no se dijo, se usan las que tenga conectadas. */
        List<String> redes,

        /** URLs de fotos propias que ya subió para esta imagen. */
        List<String> fotos,

        /** Qué quiere que pase: que escriban, que vayan al local, que conozcan algo. */
        String queQuiereQuePase,

        /** El texto que debe ir DENTRO de la imagen, si pidió alguno. */
        String textoEnLaImagen,

        /** Cuándo es ("el viernes", "hoy hasta las 6"). Nulo si no aplica. */
        String cuando,

        /** Lo demás que dijo y conviene recordar, en una línea. */
        String notas,

        /**
         * La versión que eligió de lo que ya se creó, si eligió alguna.
         *
         * <p>Es lo que hace que "acércame más los tacos" afine ESA y no
         * devuelva una distinta. Sin esto, cada vuelta empieza de cero y la
         * persona siente que no la escuchan — y cada vuelta cuesta créditos.
         */
        String piezaElegida) {

    public FichaDeImagen {
        redes = redes == null ? List.of() : List.copyOf(redes);
        fotos = fotos == null ? List.of() : List.copyOf(fotos);
    }

    /** Una ficha recién abierta: no se sabe nada todavía. */
    public static FichaDeImagen vacia() {
        return new FichaDeImagen(null, null, List.of(), List.of(), null, null, null, null, null);
    }

    /**
     * Lo que falta para poder crear, en palabras de la persona.
     *
     * <p>Son dos cosas y nada más. Se podría exigir el objetivo, el texto y la
     * fecha, y la imagen saldría mejor afinada — pero cada campo obligatorio
     * es una pregunta más antes de ver algo, y lo que hace abandonar es no ver
     * nada. Lo demás se pregunta si hay ocasión, no se exige.
     */
    public List<String> queFalta() {
        List<String> falta = new ArrayList<>();
        if (vacio(queSeAnuncia)) {
            falta.add("qué quieres anunciar");
        }
        if (vacio(formato)) {
            falta.add("si es para el muro, una historia o un carrusel");
        }
        return falta;
    }

    public boolean completa() {
        return queFalta().isEmpty();
    }

    /** Lo que ya se sabe, para enseñárselo al modelo sin que vuelva a preguntarlo. */
    public String resumen() {
        StringBuilder sb = new StringBuilder();
        linea(sb, "Qué se anuncia", queSeAnuncia);
        linea(sb, "Formato", formato);
        linea(sb, "Redes", redes.isEmpty() ? null : String.join(", ", redes));
        linea(sb, "Fotos propias", fotos.isEmpty() ? null : fotos.size() + " ya subidas");
        linea(sb, "Qué quiere que pase", queQuiereQuePase);
        linea(sb, "Texto dentro de la imagen", textoEnLaImagen);
        linea(sb, "Cuándo", cuando);
        linea(sb, "Notas", notas);
        if (!vacio(piezaElegida)) {
            sb.append("- Ya eligió una versión y la estamos afinando (no empieces de cero)\n");
        }
        return sb.isEmpty() ? "(todavía no se sabe nada)" : sb.toString();
    }

    /**
     * La ficha de antes con lo nuevo encima.
     *
     * <p>Lo que el modelo no mencione en un turno se conserva: si en el turno
     * tres habla solo del texto, no se puede perder el formato que se acordó
     * en el turno uno. Olvidar lo ya dicho es lo que hace que una conversación
     * se sienta rota.
     */
    public FichaDeImagen con(FichaDeImagen nueva) {
        if (nueva == null) {
            return this;
        }
        return new FichaDeImagen(
                elegir(nueva.queSeAnuncia, queSeAnuncia),
                elegir(nueva.formato, formato),
                nueva.redes.isEmpty() ? redes : nueva.redes,
                nueva.fotos.isEmpty() ? fotos : nueva.fotos,
                elegir(nueva.queQuiereQuePase, queQuiereQuePase),
                elegir(nueva.textoEnLaImagen, textoEnLaImagen),
                elegir(nueva.cuando, cuando),
                elegir(nueva.notas, notas),
                // La pieza elegida NO la decide el modelo: la elige la persona
                // tocándola, y se pone con conPieza().
                piezaElegida);
    }

    /** La ficha con estas fotos añadidas. */
    public FichaDeImagen conFotos(List<String> nuevas) {
        if (nuevas == null || nuevas.isEmpty()) {
            return this;
        }
        List<String> todas = new ArrayList<>(fotos);
        for (String f : nuevas) {
            if (f != null && !f.isBlank() && !todas.contains(f)) {
                todas.add(f);
            }
        }
        return new FichaDeImagen(queSeAnuncia, formato, redes, todas, queQuiereQuePase,
                textoEnLaImagen, cuando, notas, piezaElegida);
    }

    /** La ficha con la versión que la persona eligió para seguir afinándola. */
    public FichaDeImagen conPieza(String url) {
        return new FichaDeImagen(queSeAnuncia, formato, redes, fotos, queQuiereQuePase,
                textoEnLaImagen, cuando, notas, url == null || url.isBlank() ? null : url.strip());
    }

    private static String elegir(String nuevo, String viejo) {
        return vacio(nuevo) ? viejo : nuevo.strip();
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static void linea(StringBuilder sb, String etiqueta, String valor) {
        if (!vacio(valor)) {
            sb.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }
}
