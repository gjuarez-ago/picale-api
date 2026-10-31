package com.metricol.api.controller;

import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.reels.MusicaDeReels;
import com.metricol.api.service.reels.ReelMusicCatalog;

/** La música de la cuenta: en los reels de fotos, de fondo en los videos que se suben, y con qué ambiente. */
@RestController
@RequestMapping("/api/v1/musica/ajustes")
public class MusicaAjustesController {

    /** Lo guardado más los ambientes que se pueden elegir, para no repetir la lista en la web. */
    public record Respuesta(boolean activo, String ambiente, boolean videos, Set<String> ambientes) {
    }

    private final MusicaDeReels musica;
    private final ReelMusicCatalog catalogo;
    private final PermissionService permisos;

    public MusicaAjustesController(MusicaDeReels musica, ReelMusicCatalog catalogo, PermissionService permisos) {
        this.musica = musica;
        this.catalogo = catalogo;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Respuesta>> ver(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(respuesta(musica.ajustes(currentUser.getWorkspace().getId()))));
    }

    /** Lo cambia quien puede crear publicaciones, como los subtítulos automáticos. */
    @PutMapping
    public ResponseEntity<ApiResponse<Respuesta>> guardar(
            @AuthenticationPrincipal User currentUser, @RequestBody MusicaDeReels.Ajustes ajustes) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(
                respuesta(musica.guardar(currentUser.getWorkspace().getId(), ajustes))));
    }

    private Respuesta respuesta(MusicaDeReels.Ajustes ajustes) {
        return new Respuesta(ajustes.activo(), ajustes.ambiente(), Boolean.TRUE.equals(ajustes.videos()),
                catalogo.ambientes());
    }
}
