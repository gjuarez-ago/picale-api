package com.metricol.api.controller;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.media.subtitulos.EstiloSubtitulos;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;
import com.metricol.api.service.media.subtitulos.SubtitulosService;
import com.metricol.api.service.reels.MusicaDeReels;
import com.metricol.api.service.reels.ReelMusicCatalog;

/**
 * Todo lo que se le hace solo a un video, en una llamada: subtítulos,
 * recortes, logo y música. Para la app, donde cada interruptor es un toque.
 *
 * <p>El cambio es parcial: lo que no viene (nulo) se queda como está. Así un
 * toque manda solo lo que tocó, en vez de todo el formulario, y dos toques
 * seguidos no se pisan lo que el otro cambió. Para volver estilo, color o
 * ambiente a "que vaya cambiando" se manda vacío ({@code ""}).
 */
@RestController
@RequestMapping("/api/v1/videos/ajustes")
public class VideosAjustesController {

    public record Ajustes(
            Boolean subtitulos, String estilo, String color,
            Boolean recortes, Boolean logo, Boolean hayLogo,
            Boolean musicaVideos, Boolean musicaReels, String ambiente, Set<String> ambientes) {
    }

    private final SubtitulosAutomaticos automaticos;
    private final MusicaDeReels musica;
    private final ReelMusicCatalog catalogo;
    private final PermissionService permisos;

    public VideosAjustesController(SubtitulosAutomaticos automaticos, MusicaDeReels musica,
            ReelMusicCatalog catalogo, PermissionService permisos) {
        this.automaticos = automaticos;
        this.musica = musica;
        this.catalogo = catalogo;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Ajustes>> ver(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(leer(currentUser.getWorkspace().getId())));
    }

    /** Lo cambia quien puede crear publicaciones, como en la web. */
    @PatchMapping
    public ResponseEntity<ApiResponse<Ajustes>> cambiar(@AuthenticationPrincipal User currentUser,
            @RequestBody Ajustes cambio) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        UUID ws = currentUser.getWorkspace().getId();

        if (cambio.subtitulos() != null || cambio.estilo() != null || cambio.color() != null
                || cambio.recortes() != null || cambio.logo() != null) {
            SubtitulosAutomaticos.Ajustes antes = automaticos.ajustes(ws);
            automaticos.guardar(ws, new SubtitulosAutomaticos.Ajustes(
                    cambio.subtitulos() != null ? cambio.subtitulos() : antes.activo(),
                    cambio.estilo() == null ? antes.estilo() : enumDe(EstiloSubtitulos.class, cambio.estilo()),
                    cambio.color() == null ? antes.color() : enumDe(SubtitulosService.Color.class, cambio.color()),
                    cambio.recortes(), cambio.logo(), null));
        }
        if (cambio.musicaVideos() != null || cambio.musicaReels() != null || cambio.ambiente() != null) {
            MusicaDeReels.Ajustes antes = musica.ajustes(ws);
            musica.guardar(ws, new MusicaDeReels.Ajustes(
                    cambio.musicaReels() != null ? cambio.musicaReels() : antes.activo(),
                    cambio.ambiente() == null ? antes.ambiente() : cambio.ambiente(),
                    cambio.musicaVideos()));
        }
        return ResponseEntity.ok(ApiResponse.success(leer(ws)));
    }

    private Ajustes leer(UUID ws) {
        SubtitulosAutomaticos.Ajustes s = automaticos.ajustes(ws);
        MusicaDeReels.Ajustes m = musica.ajustes(ws);
        return new Ajustes(s.activo(), s.estilo() == null ? null : s.estilo().name(),
                s.color() == null ? null : s.color().name(), s.recortes(), s.logo(), s.hayLogo(),
                Boolean.TRUE.equals(m.videos()), m.activo(), m.ambiente(), catalogo.ambientes());
    }

    /** Vacío = "que vaya cambiando"; uno que no existe es un error que se le dice. */
    private static <E extends Enum<E>> E enumDe(Class<E> tipo, String valor) {
        if (valor.isBlank()) return null;
        try {
            return Enum.valueOf(tipo, valor.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Esa opción no existe.");
        }
    }
}
