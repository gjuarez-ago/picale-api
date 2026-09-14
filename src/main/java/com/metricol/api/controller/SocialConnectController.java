package com.metricol.api.controller;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.SocialAccountService;
import com.metricol.api.service.social.UploadPostConnectService;

/**
 * Conectar las cuentas de redes del workspace con upload-post.com. Portado del
 * {@code UploadPostTestController} de vivento366.api.
 *
 * <p>Vive aparte de {@link SocialAccountController} a propósito: ese expone
 * las {@code social_accounts} que metricol guarda, y este el flujo de conexión
 * contra el proveedor. Mezclarlos en una sola ruta haría chocar
 * {@code DELETE /{id}} —un UUID— con las subrutas por plataforma.
 */
@RestController
@RequestMapping("/api/v1/social-connect")
public class SocialConnectController {

    private final UploadPostConnectService connectService;
    private final SocialAccountService accountService;
    private final WorkspaceRepository workspaceRepository;

    public SocialConnectController(
            UploadPostConnectService connectService,
            SocialAccountService accountService,
            WorkspaceRepository workspaceRepository) {

        this.connectService = connectService;
        this.accountService = accountService;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * El enlace a la pantalla de conexión hospedada por upload-post, que
     * ofrece todas las redes de una vez. La app lo abre en el navegador.
     */
    @GetMapping("/link")
    public ResponseEntity<ApiResponse<Map<String, String>>> connectLink(
            @AuthenticationPrincipal User currentUser) {

        String url = connectService.connectLink(workspaceOf(currentUser));
        return ResponseEntity.ok(ApiResponse.success(Map.of("accessUrl", url)));
    }

    /**
     * Qué redes están conectadas. Además de contestar, refleja el resultado en
     * las {@code social_accounts} de metricol, así que sirve de sincronización:
     * conviene llamarlo al volver del flujo de conexión.
     */
    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connections(
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                connectService.connectionStatus(workspaceOf(currentUser))));
    }

    /**
     * El enlace para conectar UNA red.
     *
     * <p>Devuelve la pantalla de upload-post filtrada a esa red, no el OAuth
     * crudo de la plataforma. El nombre de la ruta se conserva para no
     * romper a las apps instaladas, pero lo que hace por dentro cambió, y la
     * razón importa:
     *
     * <p>Mandar a la persona al diálogo de Meta directamente la deja frente a
     * una lista de Páginas SIN marcar. Darle a continuar sin marcar ninguna
     * —lo más fácil de hacer— autoriza la cuenta pero no concede ninguna
     * Página, así que no queda dónde publicar y la app la sigue enseñando
     * como no conectada. Era un callejón del que solo se salía quitando la
     * app desde los ajustes de Facebook.
     *
     * <p>La pantalla de upload-post guía ese paso. Es su flujo y lo mantienen
     * ellos; nosotros solo le decimos qué red enseñar.
     */
    @GetMapping("/{platform}/oauth-start")
    public ResponseEntity<ApiResponse<Map<String, String>>> oauthStart(
            @PathVariable String platform,
            @AuthenticationPrincipal User currentUser) {

        // Volver a conectarla levanta el apagado manual, si lo había: tocar
        // "Conectar" en una red que se apagó es la señal de que se cambió de
        // opinión. Sin esto quedaría apagada para siempre — el sync la
        // seguiría saltando por mucho que se autorizara de nuevo.
        accountService.reactivarPlatform(plataformaDe(platform));

        String url = connectService.connectLink(workspaceOf(currentUser), List.of(platform));
        // Se responde con las dos llaves: `authorizeUrl` es la que leen las
        // apps ya instaladas y `accessUrl` la que nombra de verdad lo que es.
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "authorizeUrl", url,
                "accessUrl", url)));
    }

    /**
     * Apaga una red para este workspace: deja de salir donde se publica.
     *
     * <p>Va por plataforma y no por id de cuenta porque es lo que enseña la
     * pantalla — una fila por red — y porque quien la toca no sabe ni tiene
     * por qué saber que detrás hay una fila con UUID.
     *
     * <p>No revoca el permiso en la red ni en upload-post; ver
     * {@code SocialAccountService.disconnectPlatform}.
     */
    @DeleteMapping("/{platform}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable String platform) {
        accountService.disconnectPlatform(plataformaDe(platform));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * La plataforma que nombra la ruta, o un 400 con el nombre bueno.
     *
     * <p>Sin esto, un nombre desconocido sale como un 500 de
     * {@code IllegalArgumentException}, que en la app se lee como "algo se
     * rompió" cuando en realidad la ruta estaba mal escrita.
     */
    private Platform plataformaDe(String platform) {
        try {
            return Platform.valueOf(platform.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Red desconocida: " + platform);
        }
    }

    /**
     * Las páginas de Facebook que administra la cuenta conectada. Facebook no
     * deja publicar en un perfil personal, solo en una de sus páginas.
     *
     * <p>Con una sola página ya no hace falta llamar aquí: {@code connections}
     * la fija sola. Esto queda para cuando hay varias y hay que elegir.
     */
    @GetMapping("/facebook/pages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> facebookPages(
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                connectService.facebookPages(workspaceOf(currentUser))));
    }

    @PostMapping("/facebook/pages/{pageId}/pin")
    public ResponseEntity<ApiResponse<Void>> pinFacebookPage(
            @PathVariable String pageId,
            @AuthenticationPrincipal User currentUser) {

        connectService.pinFacebookPage(workspaceOf(currentUser), pageId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Igual que {@link #facebookPages}, para organizaciones de LinkedIn. */
    @GetMapping("/linkedin/pages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedinPages(
            @AuthenticationPrincipal User currentUser) {

        return ResponseEntity.ok(ApiResponse.success(
                connectService.linkedinPages(workspaceOf(currentUser))));
    }

    @PostMapping("/linkedin/pages/{pageId}/pin")
    public ResponseEntity<ApiResponse<Void>> pinLinkedinPage(
            @PathVariable String pageId,
            @AuthenticationPrincipal User currentUser) {

        connectService.pinLinkedinPage(workspaceOf(currentUser), pageId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Se relee del repositorio en vez de usar el que trae el usuario
     * autenticado: {@code connectLink} guarda el perfil de upload-post en el
     * workspace, y hacerlo sobre una instancia que viene del token —fuera de
     * la sesión de persistencia— dejaría el cambio sin escribirse.
     */
    private Workspace workspaceOf(User currentUser) {
        return workspaceRepository.findById(currentUser.getWorkspace().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));
    }
}
