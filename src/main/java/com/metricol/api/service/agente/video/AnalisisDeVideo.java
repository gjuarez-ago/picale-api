package com.metricol.api.service.agente.video;

import java.util.List;

import com.metricol.api.service.agente.DecisorDelAgente;
import com.metricol.api.service.agente.RevisorDeMarca;

/**
 * Lo que el Analista entendió de un video: qué pasa, qué se dice, qué tomas
 * valen y cuál es el mejor tramo. Solo observa; decidir es del
 * {@link DecisorDeVideo}.
 *
 * <p>Se guarda en el archivo ({@code MediaAsset.agenteAnalisis}) para que los
 * demás agentes —el editor que saque las mejores tomas, el que agregue audio—
 * lo reutilicen sin volver a pagar por mirar y escuchar el video.
 *
 * @param descripcion   qué pasa, de principio a fin
 * @param tipo          RECORRIDO, DEMOSTRACION, TESTIMONIO, DETRAS_DE_CAMARAS,
 *                      EVENTO, PROMOCION, PRODUCTO u OTRO
 * @param efimero       algo del momento (detrás de cámaras, aviso del día): va mejor de historia
 * @param portadaSegundo el segundo del cuadro más claro y representativo
 * @param tomas         cada cuadro mirado, con su segundo y su nota
 * @param tramoInicio   el mejor tramo de hasta 90 s: dónde empieza
 * @param tramoFin      y dónde acaba
 * @param transcripcion lo que se dice (recortado), vacío si no se habla
 */
public record AnalisisDeVideo(
        RevisorDeMarca.Veredicto veredicto,
        String motivo,
        String descripcion,
        String idea,
        String tipo,
        int calidad,
        String queFalla,
        boolean arreglable,
        boolean efimero,
        boolean necesitaTexto,
        DecisorDelAgente.Intencion intencion,
        double portadaSegundo,
        List<Toma> tomas,
        double tramoInicio,
        double tramoFin,
        String transcripcion) {

    /** Un momento del video: cuándo, qué tan buena es la toma (1–5) y qué se ve. */
    public record Toma(double segundo, int nota, String que) {
    }

    public boolean hayVoz() {
        return transcripcion != null && !transcripcion.isBlank();
    }

    /**
     * El diagnóstico con la forma del de las fotos: lo usan la mezcla de la
     * semana (qué clase de publicación es) y lo que se comparte con fotos.
     */
    public DecisorDelAgente.Diagnostico comoDiagnostico() {
        String tipoComun = switch (tipo == null ? "" : tipo) {
            case "PROMOCION" -> "PROMOCION";
            case "PRODUCTO", "DEMOSTRACION" -> "PRODUCTO";
            case "TESTIMONIO" -> "TESTIMONIO";
            case "EVENTO" -> "EVENTO";
            case "DETRAS_DE_CAMARAS" -> "EQUIPO";
            case "RECORRIDO" -> "LUGAR";
            default -> "OTRO";
        };
        return new DecisorDelAgente.Diagnostico(calidad, queFalla, arreglable, 4, false, necesitaTexto, intencion,
                tipoComun);
    }
}
