package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.WorkspaceMembershipService;
import com.metricol.api.service.agente.AgenteService;

/**
 * El agente de la cuenta actual: su switch, su bandeja "Por aprobar", y lo que
 * dejó en observación o descartó.
 *
 * <p>Aprobar pide programar, porque es lo que hace: deja la publicación
 * programada. Descartar pide borrar. Decidir sobre una foto en observación
 * pide crear, porque lo que sigue es preparar un borrador.
 */
@RestController
@RequestMapping("/api/v1/agente")
public class AgenteController {

    private final AgenteService agente;
    private final PermissionService permisos;
    private final WorkspaceMembershipService membresias;
    private final com.metricol.api.service.agente.CuentaAparte otraCuenta;
    private final com.metricol.api.service.metricas.LoQueFunciona loQueFunciona;
    private final com.metricol.api.service.social.UbicacionDelNegocio ubicacion;
    private final com.metricol.api.service.agente.plan.PlanDelAgente plan;
    private final com.metricol.api.service.agente.foto.MejoraBajoDemanda mejoras;
    private final com.metricol.api.service.agente.PresupuestoDelAsistente presupuesto;

    public AgenteController(AgenteService agente, PermissionService permisos, WorkspaceMembershipService membresias,
            com.metricol.api.service.agente.CuentaAparte otraCuenta,
            com.metricol.api.service.metricas.LoQueFunciona loQueFunciona,
            com.metricol.api.service.social.UbicacionDelNegocio ubicacion,
            com.metricol.api.service.agente.plan.PlanDelAgente plan,
            com.metricol.api.service.agente.foto.MejoraBajoDemanda mejoras,
            com.metricol.api.service.agente.PresupuestoDelAsistente presupuesto) {
        this.plan = plan;
        this.mejoras = mejoras;
        this.presupuesto = presupuesto;
        this.loQueFunciona = loQueFunciona;
        this.ubicacion = ubicacion;
        this.agente = agente;
        this.permisos = permisos;
        this.membresias = membresias;
        this.otraCuenta = otraCuenta;
    }

    /**
     * Todas las cuentas de la persona con lo que espera en cada una: la vista
     * del community manager. Solo las suyas: sale de sus membresías.
     */
    @GetMapping("/cuentas")
    public ResponseEntity<ApiResponse<List<AgenteService.Cuenta>>> cuentas(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(agente.resumen(membresias.misWorkspaces(currentUser))));
    }

    public record Switch(boolean activo) {
    }

    public record Decision(boolean va) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AgenteService.Estado>> estado(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(agente.estado(ws(currentUser))));
    }

    /** Lo que el asistente planea por su cuenta: las fechas que vienen y las fotos que pidió esta semana. */
    @GetMapping("/plan")
    public ResponseEntity<ApiResponse<com.metricol.api.service.agente.plan.PlanDelAgente.Plan>> plan(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(plan.plan(ws(currentUser))));
    }

    /** Lo que le funciona a la cuenta: sus mejores horas, sus hashtags y sus publicaciones que más rindieron. */
    @GetMapping("/aprendizaje")
    public ResponseEntity<ApiResponse<com.metricol.api.service.metricas.LoQueFunciona.Resumen>> aprendizaje(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(loQueFunciona.resumen(ws(currentUser))));
    }

    /** La ubicación con la que salen las publicaciones (Instagram y TikTok). */
    @GetMapping("/ubicacion")
    public ResponseEntity<ApiResponse<com.metricol.api.service.social.UbicacionDelNegocio.Vista>> ubicacion(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(ubicacion.ver(ws(currentUser))));
    }

    @PutMapping("/ubicacion")
    public ResponseEntity<ApiResponse<com.metricol.api.service.social.UbicacionDelNegocio.Vista>> guardarUbicacion(
            @AuthenticationPrincipal User currentUser,
            @RequestBody com.metricol.api.service.social.UbicacionDelNegocio.Pedido pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(ubicacion.guardar(ws(currentUser), pedido)));
    }

    /** Busca el lugar en TikTok, que pide elegirlo de su lista. */
    @GetMapping("/ubicacion/tiktok")
    public ResponseEntity<ApiResponse<List<com.metricol.api.service.social.UploadPostClient.LugarTiktok>>> lugaresTiktok(
            @AuthenticationPrincipal User currentUser, @RequestParam("q") String q) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(ubicacion.buscarEnTiktok(ws(currentUser), q)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<AgenteService.Estado>> encender(
            @AuthenticationPrincipal User currentUser, @RequestBody Switch pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(agente.encender(ws(currentUser), pedido.activo())));
    }

    public record HorarioPedido(List<Integer> dias, int desde, int hasta) {
    }

    public record CambioPedido(String cambio) {
    }

    /** ¿Le cambiamos algo? El agente rehace la propuesta con lo que se pidió. */
    /** "Mejorarla con IA": cuesta créditos y se hace en segundo plano; el aviso dice cuándo está. */
    @PostMapping("/propuestas/{id}/mejorar")
    public ResponseEntity<ApiResponse<Void>> mejorar(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        mejoras.pedir(id, ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** "Prepárala": la pieza para una fecha que le toca al negocio. Cuesta créditos. */
    @PostMapping("/plan/fechas/{clave}/preparar")
    public ResponseEntity<ApiResponse<Void>> prepararFecha(@AuthenticationPrincipal User currentUser,
            @PathVariable String clave) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        plan.prepararFecha(ws(currentUser), clave);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** "Prepárala": una pieza de su oficio para que la cuenta no se quede callada. Cuesta créditos. */
    @PostMapping("/plan/relleno/preparar")
    public ResponseEntity<ApiResponse<Void>> prepararRelleno(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        plan.prepararRelleno(ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record PresupuestoPedido(int mensual) {
    }

    /** Cuántos créditos al mes puede usar el asistente en mejoras y diseños, y cuántos lleva. */
    @GetMapping("/presupuesto")
    public ResponseEntity<ApiResponse<com.metricol.api.service.agente.PresupuestoDelAsistente.Estado>> presupuesto(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(presupuesto.estado(ws(currentUser))));
    }

    @PutMapping("/presupuesto")
    public ResponseEntity<ApiResponse<com.metricol.api.service.agente.PresupuestoDelAsistente.Estado>> guardarPresupuesto(
            @AuthenticationPrincipal User currentUser, @RequestBody PresupuestoPedido pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(presupuesto.guardar(ws(currentUser), pedido.mensual())));
    }

    @PostMapping("/propuestas/{id}/cambiar")
    public ResponseEntity<ApiResponse<List<PostResponse>>> cambiar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id, @RequestBody CambioPedido pedido) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(agente.cambiar(id, pedido.cambio(), ws(currentUser))));
    }

    /**
     * La bandeja de todas las cuentas: lo que espera aprobación en cada una,
     * con lo que la persona puede hacer en cada cuenta según su rol ahí.
     */
    @GetMapping("/todas")
    public ResponseEntity<ApiResponse<List<AgenteService.PropuestaDeCuenta>>> todas(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(agente.bandejaDeTodas(membresias.misWorkspaces(currentUser))));
    }

    /** Aprobar desde la bandeja de todas, en la cuenta que sea, sin cambiarse a ella. */
    @PostMapping("/cuentas/{cuentaId}/propuestas/{id}/aprobar")
    public ResponseEntity<ApiResponse<PostResponse>> aprobarEn(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID cuentaId, @PathVariable UUID id) {
        exigirEn(currentUser, cuentaId, "POST_SCHEDULE");
        // En un hilo limpio: en el de la petición la sesión ya está atada a la
        // cuenta actual, y la propuesta se buscaría (y se encolaría) en la equivocada.
        return ResponseEntity.ok(ApiResponse.success(otraCuenta.en(cuentaId, () -> agente.aprobar(id, cuentaId))));
    }

    @PostMapping("/cuentas/{cuentaId}/propuestas/{id}/descartar")
    public ResponseEntity<ApiResponse<Void>> descartarEn(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID cuentaId, @PathVariable UUID id) {
        exigirEn(currentUser, cuentaId, "POST_DELETE");
        otraCuenta.en(cuentaId, () -> agente.descartar(id));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * El permiso en ESA cuenta, no en la actual: la persona puede ser dueña en
     * una y solo redactora en otra. Una cuenta que no es suya se ve igual que
     * una sin permiso.
     */
    private void exigirEn(User currentUser, UUID cuentaId, String permiso) {
        boolean puede = membresias.misWorkspaces(currentUser).stream()
                .anyMatch(m -> m.id().equals(cuentaId) && !m.archivado()
                        && m.permisos() != null && m.permisos().contains(permiso));
        if (!puede) {
            throw new com.metricol.api.exception.ForbiddenException("No tienes permiso para hacer esto en esa cuenta.");
        }
    }

    @PutMapping("/horario")
    public ResponseEntity<ApiResponse<AgenteService.Estado>> horario(
            @AuthenticationPrincipal User currentUser, @RequestBody HorarioPedido pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(
                agente.guardarHorario(ws(currentUser), pedido.dias(), pedido.desde(), pedido.hasta())));
    }

    /** Pausa de emergencia: apaga el agente y devuelve a "Por aprobar" lo que había programado. */
    @PostMapping("/pausar")
    public ResponseEntity<ApiResponse<AgenteService.Estado>> pausar(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        agente.pausar(ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(agente.estado(ws(currentUser))));
    }

    @GetMapping("/propuestas")
    public ResponseEntity<ApiResponse<List<PostResponse>>> propuestas() {
        return ResponseEntity.ok(ApiResponse.success(agente.propuestas()));
    }

    @PostMapping("/propuestas/{id}/aprobar")
    public ResponseEntity<ApiResponse<PostResponse>> aprobar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        return ResponseEntity.ok(ApiResponse.success(agente.aprobar(id, ws(currentUser))));
    }

    @PostMapping("/propuestas/aprobar-todas")
    public ResponseEntity<ApiResponse<AgenteService.Lote>> aprobarTodas(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        return ResponseEntity.ok(ApiResponse.success(agente.aprobarTodas(ws(currentUser))));
    }

    @PostMapping("/propuestas/{id}/descartar")
    public ResponseEntity<ApiResponse<Void>> descartar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_DELETE);
        agente.descartar(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Probar a mano desde Contenido: el agente revisa esta foto ya, aunque esté
     * apagado. Pide crear, porque lo que sale es un borrador.
     */
    @PostMapping("/archivos/{id}/revisar")
    public ResponseEntity<ApiResponse<AgenteService.Resultado>> revisar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(agente.revisarAhora(id, ws(currentUser))));
    }

    /** Una vuelta del agente ya, sin esperar al proceso de fondo. Devuelve cuántas revisó. */
    @PostMapping("/revisar-ahora")
    public ResponseEntity<ApiResponse<Integer>> revisarAhora(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(agente.vueltaAhora(ws(currentUser))));
    }

    /** {@code etapa}: OBSERVACION o DESCARTADA. */
    @GetMapping("/archivos")
    public ResponseEntity<ApiResponse<List<MediaAssetResponse>>> archivos(@RequestParam EtapaAgente etapa) {
        return ResponseEntity.ok(ApiResponse.success(agente.archivos(etapa)));
    }

    @PostMapping("/archivos/{id}/decidir")
    public ResponseEntity<ApiResponse<Void>> decidir(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id, @RequestBody Decision pedido) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        agente.decidir(id, pedido.va(), ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static UUID ws(User currentUser) {
        return currentUser.getWorkspace().getId();
    }
}
