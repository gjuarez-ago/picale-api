package com.metricol.api.models.response.root;

import java.util.UUID;

/**
 * Alguien que administra la plataforma.
 *
 * @param raiz si es la cuenta raíz: la marca se la pone el arranque, así que
 *             no se le puede quitar desde la pantalla
 * @param tu   si es quien está viendo la lista: tampoco se puede quitar a sí
 *             mismo, para que nunca se quede la plataforma sin nadie
 */
public record AdministradorResponse(UUID userId, String name, String email, boolean raiz, boolean tu) {
}
