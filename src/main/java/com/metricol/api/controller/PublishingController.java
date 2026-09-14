package com.metricol.api.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.config.DailyQuotaProperties;
import com.metricol.api.config.VideoLimitsProperties;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Platform;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.publishing.FormatRulesService;
import com.metricol.api.service.publishing.PublishQuotaService;
import com.metricol.api.service.publishing.PublishQueueService;

/**
 * Las reglas de publicación que la app necesita saber ANTES de subir algo, y
 * cómo va la cola.
 *
 * <p>Que la app las consulte en vez de traerlas escritas evita el problema de
 * siempre: una red mueve su tope, se ajusta la variable de entorno, y las
 * apps ya instaladas se enteran sin actualizarse.
 */
@RestController
@RequestMapping("/api/v1/publishing")
public class PublishingController {

    private final VideoLimitsProperties videoLimits;
    private final PublishQuotaService cuotas;
    private final PublishQueueService cola;
    private final FormatRulesService formatRules;

    public PublishingController(
            VideoLimitsProperties videoLimits,
            PublishQuotaService cuotas,
            PublishQueueService cola,
            FormatRulesService formatRules) {
        this.videoLimits = videoLimits;
        this.cuotas = cuotas;
        this.cola = cola;
        this.formatRules = formatRules;
    }

    /**
     * Todo lo que condiciona una publicación: duración de video por red,
     * cuánto queda de la cuota de hoy y cuánto trabajo hay en la cola.
     *
     * <p>Va en una sola respuesta a propósito. La pantalla de captura necesita
     * las tres cosas antes de dejar publicar, y tres consultas separadas
     * habrían sido tres latencias y tres formas de quedarse a medias.
     */
    @GetMapping("/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> limits(
            @AuthenticationPrincipal User currentUser) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("video", topesDeVideo());
        cuerpo.put("daily", cuotaDiaria(currentUser));
        cuerpo.put("queue", estadoDeLaCola(currentUser));
        return ResponseEntity.ok(ApiResponse.success(cuerpo));
    }

    /**
     * Qué se puede publicar en cada formato.
     *
     * <p>Va aparte de {@code /limits} a propósito: {@code /limits} cambia con
     * el día —la cuota gastada, la cola— y esto no cambia nunca salvo que se
     * despliegue. Juntarlos obligaría a volver a pedir la tabla entera cada
     * vez que la pantalla quiere saber cuánta cuota queda.
     *
     * <p>Que la pantalla la pida en vez de traerla escrita es lo que impide
     * que vuelvan a separarse: la app cortaba los videos en 90 segundos por su
     * cuenta mientras el servidor creía admitir diez minutos en TikTok, y
     * nadie se enteró hasta ir a leerlo.
     *
     * <p>Solo lo que se ofrece: el video largo está en el enum pero no en esta
     * respuesta, así que la pantalla no tiene que saber nada de él para
     * ignorarlo — y el día que se lance aparece solo.
     */
    @GetMapping("/formats")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> formats() {
        List<Map<String, Object>> formatos = new ArrayList<>();

        for (FormatRulesService.Regla regla : formatRules.ofrecidas()) {
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("format", regla.formato().name());
            entrada.put("label", regla.label());
            entrada.put("platforms", regla.redes().stream().map(Platform::name).sorted().toList());
            entrada.put("maxFiles", regla.maxArchivos());
            entrada.put("allowsPhoto", regla.admiteFoto());
            entrada.put("allowsVideo", regla.admiteVideo());
            // Nulos cuando el formato no lleva video. Se manda la llave igual
            // para que el cliente distinga "no aplica" de "no vino".
            entrada.put("minVideoSeconds", regla.minSegundos());
            entrada.put("maxVideoSeconds", regla.maxSegundos());
            entrada.put("maxVideoLabel", regla.maxSegundos() == null
                    ? null : VideoLimitsProperties.legible(regla.maxSegundos()));
            // La proporcion la exige el cliente y no el servidor: aqui solo
            // llegan URLs, y medir un video pediria descargarlo antes de
            // aceptar nada. Ver FormatRulesService.comprobarFormato.
            entrada.put("requiresVertical", regla.exigeVertical());
            formatos.add(entrada);
        }
        return ResponseEntity.ok(ApiResponse.success(formatos));
    }

    private List<Map<String, Object>> topesDeVideo() {
        List<Map<String, Object>> video = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            Integer tope = videoLimits.maxSecondsFor(platform);
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("platform", platform.name());
            entrada.put("label", platform.getLabel());
            // null = sin tope conocido. Se manda la llave igual para que el
            // cliente distinga "no hay tope" de "esta red no vino".
            entrada.put("maxVideoSeconds", tope);
            entrada.put("maxVideoLabel", tope == null ? null : VideoLimitsProperties.legible(tope));
            video.add(entrada);
        }
        return video;
    }

    private List<Map<String, Object>> cuotaDiaria(User currentUser) {
        List<Map<String, Object>> diaria = new ArrayList<>();
        for (PublishQuotaService.Estado estado : cuotas.estadoDe(currentUser.getWorkspace().getId())) {
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("platform", estado.platform().name());
            entrada.put("label", estado.platform().getLabel());
            entrada.put("used", estado.used());
            entrada.put("limit", estado.limit());
            entrada.put("remaining", estado.remaining());
            entrada.put("exhausted", estado.exhausted());
            diaria.add(entrada);
        }
        return diaria;
    }

    private Map<String, Object> estadoDeLaCola(User currentUser) {
        PublishQueueService.Resumen resumen = cola.resumen(currentUser.getWorkspace().getId());
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("mine", resumen.enColaDelWorkspace());
        estado.put("pending", resumen.pendientes());
        estado.put("running", resumen.publicando());
        estado.put("waitSeconds", resumen.esperaSegundos());
        estado.put("resetsAt", DailyQuotaProperties.siguienteReinicio().toString());
        return estado;
    }
}
