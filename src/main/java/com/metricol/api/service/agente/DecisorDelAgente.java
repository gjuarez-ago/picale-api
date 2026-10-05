package com.metricol.api.service.agente;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Qué necesita cada foto: salir tal cual, un retoque, o un diseño con IA. Y si
 * lleva logo.
 *
 * <p><b>La IA observa; esto decide.</b> La IA de visión califica la foto
 * ({@link Diagnostico}) y no decide nada. Las reglas viven aquí, en una
 * función sin red ni base de datos, por tres razones: cada decisión se puede
 * explicar paso a paso, dos fotos iguales reciben la misma, y se prueba caso
 * por caso. Un modelo que decidiera todo de una vez resolvería distinto cada
 * vez y sin poder decir por qué.
 *
 * <p>El árbol, en orden:
 * <ol>
 * <li>¿Ya es un arte terminado (flyer, pieza con texto)? Tal cual, sin logo.</li>
 * <li>¿Calidad baja? Si se arregla (luz, encuadre), retoque. Si no, solo un
 * diseño la rescata; sin mensaje que lo valga, a Observación.</li>
 * <li>¿El mensaje necesita leerse en la imagen (precio, oferta, fecha)?
 * Candidata a diseño, prioridad alta.</li>
 * <li>¿Fuerza visual? Alta, tal cual. Media y para vender, candidata a diseño
 * (media). Baja, candidata (baja).</li>
 * <li>Presupuesto: una candidata se diseña si alcanza el ritmo de créditos de
 * la semana; las de prioridad media y baja dejan uno libre para una alta.</li>
 * <li>Logo: en lo que se comparte fuera de la cuenta (producto, promoción,
 * un trabajo terminado del negocio); nunca en un arte terminado.</li>
 * </ol>
 *
 * <p>Después, el director de foto mira la foto en alta resolución
 * ({@link #conDireccion}): si un retoque fiel la vuelve profesional, el
 * tratamiento pasa a {@link Tratamiento#MEJORA} —con IA, cuidando el
 * realismo—; si ya se ve profesional, sale tal cual.
 *
 * <p>El {@code ajuste} de la cuenta mueve el umbral del paso 4: sube cuando la
 * persona descarta diseños (diseñar menos) y baja cuando los aprueba.
 */
public final class DecisorDelAgente {

    public enum Tratamiento { TAL_CUAL, RETOQUE, MEJORA, DISENO, OBSERVACION }

    public enum Prioridad { ALTA, MEDIA, BAJA }

    public enum Intencion { VENDER, INFORMAR, COMUNIDAD, CONFIANZA }

    /**
     * Lo que vio la IA. Escalas de 1 a 5.
     *
     * @param calidad       luz, nitidez, encuadre y resolución
     * @param queFalla      qué le falta, si falta algo ("oscura", "torcida")
     * @param arreglable    si lo que falla se corrige con un retoque
     * @param fuerza        si la foto sola detiene el scroll
     * @param esArte        ya trae diseño, texto o logo encima
     * @param necesitaTexto el mensaje necesita leerse en la imagen
     * @param tipo          PRODUCTO, OBRA, LUGAR, EQUIPO, EVENTO, PROMOCION, TESTIMONIO u OTRO
     */
    public record Diagnostico(int calidad, String queFalla, boolean arreglable, int fuerza, boolean esArte,
            boolean necesitaTexto, Intencion intencion, String tipo) {

        /** Una foto buena y llamativa de producto: lo que se asume si la IA no dijo más. */
        public static final Diagnostico BUENA = new Diagnostico(4, "", true, 4, false, false, Intencion.VENDER,
                "PRODUCTO");

        public Diagnostico {
            calidad = Math.max(1, Math.min(5, calidad));
            fuerza = Math.max(1, Math.min(5, fuerza));
            queFalla = queFalla == null ? "" : queFalla.strip();
            intencion = intencion == null ? Intencion.VENDER : intencion;
            tipo = tipo == null ? "OTRO" : tipo;
        }
    }

    /**
     * Lo que sabe de la cuenta.
     *
     * @param disenosDisponibles cuántos diseños caben todavía esta semana según
     *                           el ritmo de créditos ({@link RitmoDeCreditos})
     * @param ajuste             de -1 a 2: cuánto más difícil es que diseñe
     */
    public record Contexto(int disenosDisponibles, int ajuste) {
    }

    /** Qué hacer, y cada paso que llevó ahí, en palabras para la persona. */
    public record Decision(Tratamiento tratamiento, boolean logo, Prioridad prioridad, List<String> pasos) {

        public String explicacion() {
            return String.join(" ", pasos);
        }
    }

    /** OBRA: lo que hizo el negocio (una construcción, una instalación, un servicio terminado) es su portafolio. */
    private static final Set<String> CON_LOGO = Set.of("PRODUCTO", "PROMOCION", "OBRA");

    private DecisorDelAgente() {
    }

    public static Decision decidir(Diagnostico d, Contexto c) {
        List<String> pasos = new ArrayList<>();

        // 1. Un arte terminado ya está diseñado: encima no va nada.
        if (d.esArte()) {
            pasos.add("Ya es una pieza terminada, con su texto: la publico tal cual y sin logo.");
            return new Decision(Tratamiento.TAL_CUAL, false, null, pasos);
        }

        boolean retocar = false;
        Prioridad candidata = null;

        // 2. Calidad.
        if (d.calidad() <= 2) {
            String falla = d.queFalla().isBlank() ? "tiene poca calidad" : "está " + d.queFalla();
            if (d.arreglable()) {
                retocar = true;
                pasos.add("La foto " + falla + ", pero se corrige: le hago un retoque.");
            } else if (d.necesitaTexto() || d.intencion() == Intencion.VENDER) {
                candidata = d.necesitaTexto() ? Prioridad.ALTA : Prioridad.MEDIA;
                pasos.add("La foto " + falla + " y no se arregla con un retoque; solo un diseño la rescata.");
            } else {
                pasos.add("La foto " + falla + " y no se arregla: mejor pide otra.");
                return new Decision(Tratamiento.OBSERVACION, false, null, pasos);
            }
        }

        // 3. Un mensaje que tiene que leerse en la imagen.
        if (candidata == null && d.necesitaTexto()) {
            candidata = Prioridad.ALTA;
            pasos.add("El mensaje tiene que leerse en la imagen (precio, oferta o fecha): pide diseño.");
        }

        // 4. Fuerza visual, con el umbral de la cuenta.
        if (candidata == null) {
            int fuerza = d.fuerza();
            if (fuerza >= 4) {
                pasos.add("Es llamativa por sí sola (" + fuerza + "/5): no necesita diseño.");
            } else if (fuerza == 3 && d.intencion() == Intencion.VENDER && c.ajuste() < 1) {
                candidata = Prioridad.MEDIA;
                pasos.add("Está bien pero se ve plana (3/5) y es para vender: un diseño la haría rendir más.");
            } else if (fuerza <= 2 && c.ajuste() < 2) {
                candidata = Prioridad.BAJA;
                pasos.add("Por sí sola no llama la atención (" + fuerza + "/5): un diseño la ayudaría.");
            } else {
                pasos.add("Sale bien tal cual" + (c.ajuste() > 0 ? ": en tu cuenta prefieres menos diseño." : "."));
            }
        }

        // 5. Presupuesto.
        Tratamiento tratamiento = retocar ? Tratamiento.RETOQUE : Tratamiento.TAL_CUAL;
        if (candidata != null) {
            int necesita = candidata == Prioridad.ALTA ? 1 : 2;
            if (c.disenosDisponibles() >= necesita) {
                tratamiento = Tratamiento.DISENO;
                pasos.add("Alcanzan los créditos de la semana: la diseño con IA.");
            } else if (d.calidad() <= 2 && !d.arreglable()) {
                pasos.add("No quedan créditos esta semana para diseñarla, y así no conviene publicarla.");
                return new Decision(Tratamiento.OBSERVACION, false, candidata, pasos);
            } else {
                pasos.add(candidata == Prioridad.ALTA
                        ? "No quedan créditos esta semana: va " + (retocar ? "con retoque" : "tal cual") + "."
                        : "Guardo el crédito para algo más urgente: va " + (retocar ? "con retoque" : "tal cual") + ".");
            }
        }

        // 6. Logo.
        boolean logo = CON_LOGO.contains(d.tipo());
        pasos.add(logo ? "Lleva tu logo: es " + switch (d.tipo()) {
                    case "PROMOCION" -> "una promoción";
                    case "OBRA" -> "un trabajo tuyo";
                    default -> "producto";
                } + "."
                : "Sin logo: no es producto, promoción ni un trabajo tuyo.");

        return new Decision(tratamiento, logo, candidata, pasos);
    }

    /**
     * Lo que vio el director de foto, sobre lo ya decidido. Un diseño o una
     * observación no cambian: la foto no se publica como está.
     *
     * @param mejorar      el director dice que un retoque fiel la vuelve profesional
     * @param deficiencias lo que le falta, en una frase ("líneas chuecas y sombras oscuras")
     * @param hayCupo      quedan mejoras con IA hoy en el espacio
     */
    public static Decision conDireccion(Decision d, boolean mejorar, String deficiencias, boolean hayCupo) {
        if (d.tratamiento() == Tratamiento.DISENO || d.tratamiento() == Tratamiento.OBSERVACION) {
            return d;
        }
        List<String> pasos = new ArrayList<>();
        for (String paso : d.pasos()) {
            // El retoque sencillo se anuncia aquí solo si se queda.
            pasos.add(mejorar && hayCupo ? paso.replace(": le hago un retoque.", ".") : paso);
        }
        int antesDelLogo = Math.max(0, pasos.size() - 1);
        if (mejorar && hayCupo) {
            pasos.add(antesDelLogo, "Le falta " + deficiencias
                    + ": la mejoro con IA para que se vea profesional, sin cambiar nada de lo que se ve.");
            return new Decision(Tratamiento.MEJORA, d.logo(), d.prioridad(), pasos);
        }
        if (mejorar) {
            pasos.add(antesDelLogo, "Le vendría bien una mejora (" + deficiencias
                    + "), pero hoy ya no me quedan: va " + (d.tratamiento() == Tratamiento.RETOQUE ? "con un retoque sencillo." : "tal cual."));
            return new Decision(d.tratamiento(), d.logo(), d.prioridad(), pasos);
        }
        if (d.tratamiento() == Tratamiento.TAL_CUAL) {
            pasos.add(antesDelLogo, "La miré en detalle: ya se ve profesional, no le cambio nada.");
        }
        return new Decision(d.tratamiento(), d.logo(), d.prioridad(), pasos);
    }
}
