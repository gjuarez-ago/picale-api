package com.metricol.api.controller;

import com.metricol.api.service.PermissionService;
import com.metricol.api.service.ai.AiQuotaGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.models.request.AjusteRequest;
import com.metricol.api.models.request.AnalyzeMediaRequest;
import com.metricol.api.models.request.CaptionSuggestionRequest;
import com.metricol.api.models.request.ComposeRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.CaptionSuggestionResponse;
import com.metricol.api.models.response.ComposeResponse;
import com.metricol.api.service.ai.Ajuste;
import com.metricol.api.service.ai.CaptionCopywriter;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.Redactor;
import com.metricol.api.service.ai.VisorDeMedios;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final CaptionCopywriter copywriter;
    private final VisorDeMedios visor;
    private final Redactor redactor;
    private final AiQuotaGuard cupo;
    private final PermissionService permisos;

    public AiController(CaptionCopywriter copywriter, VisorDeMedios visor, Redactor redactor,
            AiQuotaGuard cupo, PermissionService permisos) {
        this.copywriter = copywriter;
        this.visor = visor;
        this.redactor = redactor;
        this.cupo = cupo;
        this.permisos = permisos;
    }

    @PostMapping("/suggest-caption")
    public ResponseEntity<ApiResponse<CaptionSuggestionResponse>> suggestCaption(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CaptionSuggestionRequest request) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        cupo.exigirCupo();
        String caption = copywriter.suggest(request.getBrief());
        return ResponseEntity.ok(ApiResponse.success(CaptionSuggestionResponse.builder().caption(caption).build()));
    }

    /**
     * Mira las imágenes y las deja descritas para cuando hagan falta.
     *
     * <p>La app la llama en cuanto se agrega una foto, sin esperar a nada. No
     * devuelve nada útil para la pantalla —el resultado queda guardado en cada
     * archivo— y por eso puede lanzarse y olvidarse.
     *
     * <p>Es lo que hace que {@link #compose} se sienta instantáneo: cuando la
     * persona termina de dictar, lo lento ya ocurrió.
     */
    @PostMapping("/analyze-media")
    public ResponseEntity<ApiResponse<Void>> analyzeMedia(
            @AuthenticationPrincipal User currentUser, @RequestBody AnalyzeMediaRequest request) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        cupo.exigirCupo();
        visor.describir(request.getMediaUrls());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * De lo que se dictó al texto de cada red, en una sola llamada.
     */
    @PostMapping("/compose")
    public ResponseEntity<ApiResponse<ComposeResponse>> compose(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody ComposeRequest request) {

        permisos.exigir(currentUser, Permission.POST_CREATE);
        cupo.exigirCupo();
        List<String> queSeVe = visor.describir(request.getMediaUrls());
        Redactor.Borrador borrador = redactor.redactar(
                request.getBrief(),
                queSeVe,
                redesDe(request.getPlatforms()),
                negocioDe(currentUser));

        Map<String, String> textos = new LinkedHashMap<>();
        borrador.textos().forEach((red, texto) -> textos.put(red.name(), texto));

        return ResponseEntity.ok(ApiResponse.success(
                new ComposeResponse(borrador.titulo(), borrador.guion(), textos)));
    }

    /**
     * El mismo texto, mas corto / mas vendedor / mas profesional.
     *
     * <p>Devuelve solo el texto retocado, para una red. Los tres botones de la
     * pantalla de revision llaman aqui.
     */
    @PostMapping("/ajustar")
    public ResponseEntity<ApiResponse<CaptionSuggestionResponse>> ajustar(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody AjusteRequest request) {

        permisos.exigir(currentUser, Permission.POST_CREATE);
        cupo.exigirCupo();
        Platform red = Platform.valueOf(request.getPlatform().trim().toUpperCase());
        Ajuste ajuste = Ajuste.valueOf(request.getAjuste().trim().toUpperCase());

        String texto = redactor.ajustar(request.getTexto(), red, ajuste);
        return ResponseEntity.ok(ApiResponse.success(
                CaptionSuggestionResponse.builder().caption(texto).build()));
    }

    /**
     * Nombres a {@link Platform}, ignorando lo que no reconozca.
     *
     * <p>Se ignora en vez de fallar: si la app manda una red que esta version
     * del servidor no conoce, tiene mas sentido escribir para las que si que
     * devolver un error por una palabra.
     */
    private static List<Platform> redesDe(List<String> nombres) {
        List<Platform> redes = new ArrayList<>();
        if (nombres == null) {
            return redes;
        }
        for (String nombre : nombres) {
            try {
                redes.add(Platform.valueOf(nombre.trim().toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException ignorada) {
                // Una red desconocida no es motivo para no escribir las demas.
            }
        }
        return redes;
    }

    /**
     * El negocio de quien pide el texto, para que la IA no escriba a ciegas.
     *
     * <p>Se arma aqui y no en el Redactor para que la capa de IA no dependa de
     * las entidades: recibe un record con cinco campos, no el workspace entero.
     *
     * <p>{@code DESCONOCIDO} si no hay workspace: un texto generico es mejor
     * que ninguno, y este endpoint ya exige sesion.
     */
    private Redactor.Negocio negocioDe(User currentUser) {
        Workspace workspace = currentUser == null ? null : currentUser.getWorkspace();
        if (workspace == null) {
            return Redactor.Negocio.DESCONOCIDO;
        }
        return new Redactor.Negocio(
                workspace.getName(),
                workspace.getGiro(),
                workspace.getCiudad(),
                workspace.getDescripcion(),
                workspace.getObjetivo(),
                MarcaDelNegocio.de(workspace.getBrandProfile()));
    }
}
