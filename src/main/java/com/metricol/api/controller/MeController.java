package com.metricol.api.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MeResponse;
import com.metricol.api.service.OrganizationService;
import com.metricol.api.service.PermissionService;

/**
 * Quién soy y qué puedo hacer aquí.
 *
 * <p>Lo piden las dos interfaces al entrar y al cambiar de espacio, para no
 * ofrecer lo que va a ser rechazado: esconder el botón de publicar a quien no
 * publica es mejor que dejarlo intentarlo y darle un error.
 *
 * <p>Eso es cortesía, no seguridad. Quien decide sigue siendo el servidor en
 * cada petición ({@link PermissionService}); esta lista solo evita el paseo.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final PermissionService permisos;
    private final OrganizationService organizaciones;

    public MeController(PermissionService permisos, OrganizationService organizaciones) {
        this.permisos = permisos;
        this.organizaciones = organizaciones;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MeResponse>> yo(@AuthenticationPrincipal User currentUser) {
        var organizacion = organizaciones.deLaSesion(currentUser);
        OrgRole papel = organizaciones.rolDe(currentUser.getId(), organizacion.getId());

        List<String> lista = permisos.permisosDe(currentUser).stream()
                .map(Enum::name)
                .sorted()
                .toList();

        return ResponseEntity.ok(ApiResponse.success(new MeResponse(
                currentUser.getId(),
                currentUser.getName(),
                currentUser.getEmail(),
                organizacion.getId(),
                organizacion.getName(),
                papel,
                papel != null && papel.administraLaOrganizacion(),
                currentUser.getWorkspace().getId(),
                currentUser.getWorkspace().getName(),
                permisos.rolDe(currentUser).name(),
                lista)));
    }
}
