package com.metricol.api.enums;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cómo trabaja un negocio, en rasgos que se prenden o no. Es el filtro de lo
 * que el asistente hace en cada cuenta: el antes y después es para quien
 * trabaja por proyecto, el precio en la imagen para quien publica precios, el
 * cuidado extra para lo regulado.
 *
 * <p>Una IA los deduce del giro y la descripción la primera vez; el dueño los
 * corrige en Marca y desde ahí mandan los suyos. Cada uno lleva lo que la IA
 * que escribe y revisa tiene que hacer distinto por tenerlo.
 */
public enum RasgoDelNegocio {

    POR_PROYECTO("Trabaja por proyecto",
            "Hace obras o encargos: lo terminado y el antes y después son su mejor carta.",
            "Trabaja por proyecto: su trabajo terminado o en proceso es su portafolio; muestralo con orgullo."),
    COTIZA("Cotiza cada trabajo",
            "No tiene precios fijos: se invita a pedir cotización, nunca se inventa un precio.",
            "Cotiza cada trabajo: nunca escribas ni inventes precios; invita a pedir una cotizacion."),
    PRODUCTO("Vende producto",
            "Lo que vende se fotografía: platos, artículos, piezas.",
            "Vende producto: lo que se ve en la foto es lo que se compra; que se antoje."),
    LOCAL("Tiene local",
            "Se le visita: el lugar se muestra y la ubicación ayuda.",
            "Tiene un local que se visita: invitar a visitarlo funciona."),
    ATIENDE_ZONA("Va con el cliente",
            "Trabaja a domicilio o en obra: importa la zona que cubre.",
            "Va hasta el cliente: menciona la zona que atiende cuando ayude."),
    EDUCA("Enseña de su tema",
            "Consejos y preguntas frecuentes de su oficio le dan confianza.",
            "Sabe de su tema: un consejo practico o un dato de su oficio genera confianza."),
    PERSONAS("Su trabajo se ve en personas",
            "Clientes, pacientes o alumnos aparecen: se pide permiso y se cuida.",
            "Su trabajo se ve en personas: cuida su imagen y su privacidad."),
    REGULADO("Tema regulado o sensible",
            "Salud, alcohol, finanzas: nada de promesas ni resultados garantizados.",
            "Su tema es regulado o sensible: no prometas resultados ni hagas afirmaciones medicas o financieras."),
    TEMPORADA("De temporada",
            "Vende más en ciertas épocas del año: el calendario lo aprovecha.",
            "Es de temporada: aprovecha la epoca del año cuando venga al caso.");

    public final String etiqueta;
    public final String descripcion;
    /** Lo que cambia para quien escribe o revisa, en una línea para el prompt. */
    public final String instruccion;

    RasgoDelNegocio(String etiqueta, String descripcion, String instruccion) {
        this.etiqueta = etiqueta;
        this.descripcion = descripcion;
        this.instruccion = instruccion;
    }

    /** Los rasgos guardados ("POR_PROYECTO,COTIZA"), sin los que no se reconocen. Nulo = nunca deducidos. */
    public static Set<RasgoDelNegocio> de(String guardados) {
        if (guardados == null) {
            return null;
        }
        return de(List.of(guardados.split(",")));
    }

    public static Set<RasgoDelNegocio> de(Collection<String> codigos) {
        Set<RasgoDelNegocio> salida = EnumSet.noneOf(RasgoDelNegocio.class);
        if (codigos != null) {
            for (String c : codigos) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                try {
                    salida.add(valueOf(c.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignorado) {
                    // Un código viejo o mal escrito no tumba los demás.
                }
            }
        }
        return salida;
    }

    /** Para guardarlos: "POR_PROYECTO,COTIZA", vacío si no tiene ninguno. */
    public static String guardar(Collection<RasgoDelNegocio> rasgos) {
        List<String> codigos = new ArrayList<>();
        for (RasgoDelNegocio r : values()) {
            if (rasgos != null && rasgos.contains(r)) {
                codigos.add(r.name());
            }
        }
        return String.join(",", codigos);
    }
}
