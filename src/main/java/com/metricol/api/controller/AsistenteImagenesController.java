package com.metricol.api.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.MensajeDeImagen;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.imagenes.HilosDeImagen;

/**
 * Crear una imagen conversando, en vez de llenando un formulario.
 *
 * <p>Conversar <b>no cuesta créditos</b>: es texto y cuesta centavos. Por eso
 * estos endpoints no tocan el saldo. Lo que se cobra es crear la imagen, y eso
 * sigue donde siempre ({@code /campaign-images/generate}).
 *
 * <p>Pide el mismo permiso que crear publicaciones: esto es el primer paso de
 * crear una.
 */
@RestController
@RequestMapping("/api/v1/asistente-imagenes")
public class AsistenteImagenesController {

    public record Dicho(String texto, List<String> fotos) {
    }

    public record Elegida(String url) {
    }

    private final HilosDeImagen hilos;
    private final PermissionService permisos;
    private final ObjectMapper mapper = new ObjectMapper();

    public AsistenteImagenesController(HilosDeImagen hilos, PermissionService permisos) {
        this.hilos = hilos;
        this.permisos = permisos;
    }

    /** Abre la conversación (o devuelve la que estuviera a medias). */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> abrir(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(cuerpo(hilos.abrir(currentUser))));
    }

    /** Empezar de cero, tirando lo que se llevaba hablado. */
    @PostMapping("/nueva")
    public ResponseEntity<ApiResponse<Map<String, Object>>> empezarDeNuevo(
            @AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(cuerpo(hilos.empezarDeNuevo(currentUser))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ver(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(cuerpo(hilos.ver(currentUser, id))));
    }

    /** Un turno: lo que dice la persona, y lo que contesta el asistente. */
    @PostMapping("/{id}/mensajes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> hablar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @RequestBody Dicho dicho) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(cuerpo(hilos.hablar(currentUser, id,
                dicho == null ? null : dicho.texto(),
                dicho == null ? null : dicho.fotos()))));
    }

    /**
     * Crea de verdad. <b>Aquí y solo aquí se gastan los créditos.</b>
     *
     * <p>Contesta el identificador del trabajo; la pantalla lo sondea con
     * {@code GET /content/jobs/{id}}, que ya existe. Crear tarda más de lo que
     * aguanta una petición web.
     */
    @PostMapping("/{id}/crear")
    public ResponseEntity<ApiResponse<Map<String, Object>>> crear(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("trabajoId", hilos.crear(currentUser, id))));
    }

    /** La persona tocó la versión que le gustó: a partir de aquí se afina esa. */
    @PostMapping("/{id}/elegir")
    public ResponseEntity<ApiResponse<Map<String, Object>>> elegir(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @RequestBody Elegida elegida) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(cuerpo(
                hilos.elegir(currentUser, id, elegida == null ? null : elegida.url()))));
    }

    private Map<String, Object> cuerpo(HilosDeImagen.Vista v) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("hiloId", v.hilo().getId());
        cuerpo.put("mensajes", v.mensajes().stream().map(this::deMensaje).toList());
        cuerpo.put("opciones", v.opciones());
        cuerpo.put("ficha", v.ficha());
        // Lo que falta, en palabras de la persona: la pantalla lo enseña tal
        // cual ("solo me falta saber...") sin tener que traducir nada.
        cuerpo.put("falta", v.ficha().queFalta());
        cuerpo.put("listoParaCrear", v.listoParaCrear());
        return cuerpo;
    }

    private Map<String, Object> deMensaje(MensajeDeImagen m) {
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("id", m.getId());
        salida.put("mio", "PERSONA".equals(m.getRol()));
        salida.put("texto", m.getTexto());
        salida.put("opciones", leerOpciones(m.getOpciones()));
        salida.put("veredicto", m.getVeredicto());
        salida.put("motivo", m.getMotivo());
        salida.put("creadoEn", m.getCreadoEn());
        return salida;
    }

    private List<String> leerOpciones(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<ArrayList<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
