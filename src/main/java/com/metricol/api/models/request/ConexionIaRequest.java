package com.metricol.api.models.request;

/**
 * Lo que manda el MCP al terminar el login de una persona.
 *
 * @param cliente     cómo se presentó el cliente de IA ("Claude", "ChatGPT"…)
 * @param clienteId   su client_id OAuth, por si hay que investigar algo
 * @param expiraEpoch cuándo vence el JWT con el que entró, en segundos Unix
 * @param alcance     "read" (solo lectura) o "write"; vacío = write
 */
public record ConexionIaRequest(String cliente, String clienteId, Long expiraEpoch, String alcance) {
}
