package com.metricol.api.service.bitacora;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AccionesIaTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode j(String texto) throws Exception {
        return json.readTree(texto);
    }

    @Test
    void guardarUnaPublicacionSonTresAccionesSegunElCuerpo() throws Exception {
        assertThat(AccionesIa.clasificar("POST", "/api/v1/posts", j("{\"caption\":\"hola\"}")).accion())
                .isEqualTo("CREAR_BORRADOR");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/posts", j("{\"scheduledAt\":\"2026-10-03T11:00:00\"}")).accion())
                .isEqualTo("PROGRAMAR_PUBLICACION");
        assertThat(AccionesIa.clasificar("PUT", "/api/v1/posts/11111111-1111-1111-1111-111111111111",
                j("{\"publishNow\":true}")).accion()).isEqualTo("PUBLICAR_AHORA");
        assertThat(AccionesIa.clasificar("PUT", "/api/v1/posts/11111111-1111-1111-1111-111111111111", j("{}")).accion())
                .isEqualTo("EDITAR_PUBLICACION");
    }

    @Test
    void sacaLaEntidadYLaCuentaDeLaRuta() {
        AccionesIa.Clasificacion c = AccionesIa.clasificar("POST",
                "/api/v1/agente/cuentas/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/propuestas/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/aprobar",
                null);

        assertThat(c.accion()).isEqualTo("APROBAR_PROPUESTA");
        assertThat(c.entidadId()).isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(c.cuentaId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        AccionesIa.Clasificacion propia = AccionesIa.clasificar("POST",
                "/api/v1/agente/propuestas/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/descartar", null);
        assertThat(propia.accion()).isEqualTo("DESCARTAR_PROPUESTA");
        assertThat(propia.cuentaId()).isNull();
    }

    @Test
    void reconoceElRestoDeLasRutasYLoDesconocidoQuedaComoOtra() throws Exception {
        assertThat(AccionesIa.clasificar("POST", "/api/v1/posts/11111111-1111-1111-1111-111111111111/cancel", null).accion())
                .isEqualTo("CANCELAR_PUBLICACION");
        assertThat(AccionesIa.clasificar("DELETE", "/api/v1/posts/11111111-1111-1111-1111-111111111111/archive", null).accion())
                .isEqualTo("RESTAURAR_PUBLICACION");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/media/upload", null).accion()).isEqualTo("SUBIR_ARCHIVO");
        assertThat(AccionesIa.clasificar("PUT", "/api/v1/workspace/brand", null).accion()).isEqualTo("ACTUALIZAR_MARCA");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/content/generate", null).accion()).isEqualTo("GENERAR_CONTENIDO");
        assertThat(AccionesIa.clasificar("PUT", "/api/v1/agente", j("{\"activo\":true}")).accion()).isEqualTo("AGENTE_ENCENDER");
        assertThat(AccionesIa.clasificar("PUT", "/api/v1/agente", j("{\"activo\":false}")).accion()).isEqualTo("AGENTE_APAGAR");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/agente/pausar", null).accion()).isEqualTo("AGENTE_PAUSAR");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/agente/archivos/11111111-1111-1111-1111-111111111111/decidir",
                j("{\"va\":true}")).accion()).isEqualTo("DECIDIR_OBSERVACION");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/workspaces/11111111-1111-1111-1111-111111111111/activar", null).accion())
                .isEqualTo("CAMBIAR_CUENTA");
        assertThat(AccionesIa.clasificar("POST", "/api/v1/algo/nuevo", null).accion()).isEqualTo("OTRA");
        assertThat(AccionesIa.etiqueta("PROGRAMAR_PUBLICACION")).isEqualTo("Programó una publicación");
        assertThat(AccionesIa.etiqueta("XYZ")).isEqualTo("XYZ");
    }

    @Test
    void elDetalleResumeLoQueHayaSinInventar() throws Exception {
        String d = AccionesIa.detalle(j("{\"cambio\":\"sin logo\"}"),
                j("{\"id\":\"x\",\"caption\":\"Jueves de pastor al 2x1, ven por los tuyos\",\"status\":\"SCHEDULED\",\"scheduledAt\":\"2026-10-03T11:00:00\"}"));

        assertThat(d).contains("texto: Jueves de pastor").contains("estado: SCHEDULED").contains("programada: 2026-10-03T11:00:00")
                .contains("cambio: sin logo");
        assertThat(AccionesIa.detalle(null, null)).isNull();
        assertThat(AccionesIa.detalle(j("{\"va\":false}"), null)).isEqualTo("decisión: no va");
    }
}
