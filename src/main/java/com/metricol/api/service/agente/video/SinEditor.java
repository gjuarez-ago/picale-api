package com.metricol.api.service.agente.video;

import org.springframework.stereotype.Component;

import com.metricol.api.entity.MediaAsset;

/**
 * El editor de video de hoy: ninguno. Ver {@link EditorDeVideo}.
 *
 * <p>Cuando llegue el agente de edición, se reemplaza esta clase (o se marca
 * la nueva como {@code @Primary}) y el resto del flujo la usa sin cambios.
 */
@Component
public class SinEditor implements EditorDeVideo {

    @Override
    public boolean puedeRecortar() {
        return false;
    }

    @Override
    public MediaAsset recortar(MediaAsset video, AnalisisDeVideo analisis, double inicio, double fin) {
        return null;
    }
}
