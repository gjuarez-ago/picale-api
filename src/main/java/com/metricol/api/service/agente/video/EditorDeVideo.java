package com.metricol.api.service.agente.video;

import com.metricol.api.entity.MediaAsset;

/**
 * El productor de video: el agente que algún día saque las mejores tomas, las
 * edite, recorte lo que sobra y le agregue audio.
 *
 * <p>Es una interfaz a propósito. El coordinador ({@code AgenteService}) y el
 * {@link DecisorDeVideo} ya saben pedirle trabajo —"recorta este tramo"— y
 * qué hacer si no puede; lo que falta es quien lo haga. Cuando exista, se
 * implementa aquí y nada más cambia: recibe el {@link AnalisisDeVideo} que ya
 * se pagó, con sus tomas y su mejor tramo, y devuelve un archivo nuevo. El
 * original nunca se toca.
 *
 * <p>Hoy la única implementación es {@link SinEditor}: no edita, y el
 * decisor manda a Observación lo que necesitaría edición, diciendo qué
 * habría que hacer.
 */
public interface EditorDeVideo {

    /** ¿Puede recortar un tramo? Si no, lo largo va a Observación con el tramo sugerido. */
    boolean puedeRecortar();

    /**
     * Recorta el tramo y devuelve el archivo nuevo, ya guardado, o {@code null}
     * si no pudo. Solo se llama si {@link #puedeRecortar()}.
     */
    MediaAsset recortar(MediaAsset video, AnalisisDeVideo analisis, double inicio, double fin);
}
