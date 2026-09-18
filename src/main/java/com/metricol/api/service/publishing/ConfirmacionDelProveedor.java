package com.metricol.api.service.publishing;

import java.util.EnumMap;
import java.util.Map;

import com.metricol.api.enums.Platform;

/**
 * Lo que se sabe de un envío ya entregado al proveedor.
 *
 * <p>La regla que sostiene todo: <b>una red que no aparece en {@code porRed}
 * es una red de la que todavía no se sabe nada</b>. No salió bien, no salió
 * mal: no se sabe. Mientras el proveedor la tenga en cola o publicándola se
 * queda fuera de este mapa, y quien lee esto la deja abierta y vuelve a
 * preguntar. Meterla como fallida por no tener el dato fue exactamente el
 * error que dejó un TikTok publicado marcado como "falló" en la app.
 *
 * @param terminado si el proveedor ya cerró el envío, bien o mal. Mientras
 *                  sea false hay que volver a preguntar.
 * @param sinRastro si el proveedor no reconoce el identificador del envío.
 *                  No es lo mismo que "sin resultados": aquí preguntar otra
 *                  vez por ese identificador no va a servir, y hay que buscar
 *                  por otro lado (el historial).
 * @param porRed    lo que dijo cada red que YA cerró, para bien o para mal.
 */
public record ConfirmacionDelProveedor(
        boolean terminado, boolean sinRastro, Map<Platform, ResultadoDeRed> porRed) {

    public static ConfirmacionDelProveedor nada() {
        return new ConfirmacionDelProveedor(false, false, Map.of());
    }

    /** El proveedor no reconoce el identificador: no hay resultado y no lo va a haber por esa vía. */
    public static ConfirmacionDelProveedor desconocida() {
        return new ConfirmacionDelProveedor(false, true, Map.of());
    }

    /**
     * Esta confirmación completada con otra fuente.
     *
     * <p>Lo que ya se sabía se conserva; de la otra solo entran las redes que
     * aquí faltaban. Es como se junta el estado del envío con el historial:
     * el primero es exacto pero a veces viene incompleto, el segundo trae
     * filas ya cerradas que el primero pudo no listar.
     */
    public ConfirmacionDelProveedor completadaCon(ConfirmacionDelProveedor otra) {
        Map<Platform, ResultadoDeRed> juntas = new EnumMap<>(Platform.class);
        juntas.putAll(otra.porRed());
        juntas.putAll(porRed());
        return new ConfirmacionDelProveedor(
                terminado() || otra.terminado(), sinRastro() && otra.sinRastro(), juntas);
    }
}
