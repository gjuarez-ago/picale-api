package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

class PreparadorDePlanDeEdicionTest {
    private final PreparadorDePlanDeEdicion preparador = DatosDeEdicion.preparadorHabilitado();

    @Test
    void leeElContratoV1EscritoIndependientementeDeLosRecordsJava() throws Exception {
        try (var input = getClass().getResourceAsStream("/media/edicion/plan-v1.json")) {
            assertThat(input).isNotNull();
            var preparado = preparador.preparar(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    DatosDeEdicion.contexto());
            assertThat(preparado.plan()).isEqualTo(DatosDeEdicion.plan());
            // Cambiar el orden de los campos Java no debe cambiar la identidad del contrato v1.
            assertThat(preparado.idempotencyKey())
                    .isEqualTo("26f8961ea4cad5a9550a33abeb1053a997b8f03f496a50dbc654fd54881fcfdc");
        }
    }

    @Test
    void mismaRecetaConOtroOrdenEspaciosONumerosEquivalentesConservaIdentidad() throws Exception {
        var plan = DatosDeEdicion.plan();
        var a = preparador.preparar(DatosDeEdicion.json(plan), DatosDeEdicion.contexto());
        ObjectNode nodo = (ObjectNode) new ObjectMapper().readTree(a.canonicalJson());
        var invertido = new ObjectMapper().createObjectNode();
        var campos = new java.util.ArrayList<String>();
        nodo.fieldNames().forEachRemaining(campos::add);
        java.util.Collections.reverse(campos);
        campos.forEach(c -> invertido.set(c, nodo.get(c)));
        var b = preparador.preparar(invertido.toPrettyString().replace("120.0", "120"), DatosDeEdicion.contexto());
        assertThat(a.idempotencyKey()).matches("[a-f0-9]{64}").isEqualTo(b.idempotencyKey());
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson());
        assertThat(preparador.preparar(a.canonicalJson(), DatosDeEdicion.contexto())).isEqualTo(a);
    }

    @Test
    void cambioDePerfilEventoOCuentaCambiaLaIdentidad() throws Exception {
        var a = preparador.preparar(DatosDeEdicion.json(DatosDeEdicion.plan()), DatosDeEdicion.contexto());
        var perfil = DatosDeEdicion.plan(PerfilEdicionDinamica.DYNAMIC, new Tramo(0, 120), DatosDeEdicion.plan().events());
        var b = preparador.preparar(DatosDeEdicion.json(perfil), DatosDeEdicion.contexto());
        var evento = DatosDeEdicion.plan(PerfilEdicionDinamica.SUBTLE, new Tramo(0, 120), List.of(DatosDeEdicion.zoom(11)));
        var c = preparador.preparar(DatosDeEdicion.json(evento), DatosDeEdicion.contexto());
        assertThat(a.idempotencyKey()).isNotEqualTo(b.idempotencyKey()).isNotEqualTo(c.idempotencyKey());

        UUID otra = UUID.randomUUID();
        var config = new EdicionDinamicaProperties();
        config.setEnabled(true);
        config.setPilotWorkspaces(java.util.Set.of(otra));
        var paraOtra = new PreparadorDePlanDeEdicion(new ControlDeEdicionDinamica(config), new ValidadorDePlanDeEdicion());
        var contexto = DatosDeEdicion.contexto();
        var contextoOtro = new ContextoEdicionVideo(otra, contexto.sourceAssetId(), contexto.sourceRevision(),
                contexto.transcriptRevision(), 120, contexto.sourceRange(), false, contexto.evidence());
        String jsonOtro = a.canonicalJson().replace(DatosDeEdicion.WS.toString(), otra.toString());
        assertThat(paraOtra.preparar(jsonOtro, contextoOtro).idempotencyKey()).isNotEqualTo(a.idempotencyKey());
    }

    static Stream<Arguments> camposNoAutorizados() {
        return Stream.of(
                Arguments.of("workspaceId", "10000000-0000-0000-0000-000000000099"),
                Arguments.of("sourceAssetId", "20000000-0000-0000-0000-000000000099"),
                Arguments.of("sourceRevision", "b".repeat(64)),
                Arguments.of("transcriptRevision", "transcript-v2"),
                Arguments.of("evidenceRevision", "evidence-v2"),
                Arguments.of("schemaVersion", "talking-video-edit-v999"),
                Arguments.of("policyVersion", "subtle-v999"),
                Arguments.of("templateCatalogVersion", "video-templates-v999"),
                Arguments.of("timeBase", "SOURCE_SECONDS"));
    }

    @ParameterizedTest
    @MethodSource("camposNoAutorizados")
    void rechazaDatosDeOtraCuentaOtraFuenteOVersionDesconocida(String campo, String valor) throws Exception {
        ObjectNode nodo = (ObjectNode) new ObjectMapper().readTree(DatosDeEdicion.json(DatosDeEdicion.plan()));
        nodo.put(campo, valor);
        assertThatIllegalArgumentException().isThrownBy(() -> preparador.preparar(nodo.toString(), DatosDeEdicion.contexto()));
    }

    static Stream<String> jsonNoValido() throws Exception {
        String base = DatosDeEdicion.json(DatosDeEdicion.plan());
        return Stream.of("", "null", "{}", "[]", "```json\n" + base + "\n```", base + " {}",
                base.replaceFirst("\\{", "{\"command\":\"ffmpeg arbitrario\","),
                base.replaceFirst("\\{", "{\"schemaVersion\":\"talking-video-edit-v1\","),
                base.replace("\"scale\":1.12", "\"scale\":\"1.12\""),
                base.replace("\"scale\":1.12", "\"scale\":null"),
                base.replace("\"scale\":1.12,", ""),
                base.replace("\"scale\":1.12", "\"scale\":NaN"),
                base.replace("\"scale\":1.12", "\"scale\":1e999"),
                base.replace("\"type\":\"PUNCH_IN\"", "\"type\":0"),
                base.replace("PUNCH_IN", "PERSON_COLLAGE"),
                base.replace("\"events\":[", "\"events\":[null,"),
                base.replace("\"reason\":\"KEY_STATEMENT\"", "\"reason\":\"KEY_STATEMENT\",\"url\":\"https://otro\""),
                " ".repeat(PreparadorDePlanDeEdicion.MAX_JSON_BYTES + 1));
    }

    @ParameterizedTest
    @MethodSource("jsonNoValido")
    void fronteraEstrictaNoAceptaComandosCamposExtraCoercionesNiJsonAmbiguo(String contenido) {
        assertThatIllegalArgumentException().isThrownBy(() -> preparador.preparar(contenido, DatosDeEdicion.contexto()));
    }

    @Test
    void nuevaFronteraNoCambiaElMapperUsadoPorEncargosHistoricos() throws Exception {
        var compartido = new ObjectMapper();
        var historico = new EncargoHistorico("PALABRA", null);
        String antes = compartido.writeValueAsString(historico);
        preparador.preparar(DatosDeEdicion.json(DatosDeEdicion.plan()), DatosDeEdicion.contexto());
        assertThat(compartido.readValue(antes, EncargoHistorico.class)).isEqualTo(historico);
    }

    record EncargoHistorico(String estilo, String musica) { }

    @Test
    void noPreparaSiElControlEstaApagadoOSiLaFuenteYaEstaEditada() throws Exception {
        var apagado = new PreparadorDePlanDeEdicion(new ControlDeEdicionDinamica(new EdicionDinamicaProperties()),
                new ValidadorDePlanDeEdicion());
        String base = DatosDeEdicion.json(DatosDeEdicion.plan());
        assertThatIllegalStateException().isThrownBy(() -> apagado.preparar(base, DatosDeEdicion.contexto()))
                .withMessageContaining("DISABLED");
        var ctx = DatosDeEdicion.contexto();
        var yaEditado = new ContextoEdicionVideo(ctx.workspaceId(), ctx.sourceAssetId(), ctx.sourceRevision(),
                ctx.transcriptRevision(), 120, ctx.sourceRange(), true, ctx.evidence());
        assertThatIllegalStateException().isThrownBy(() -> preparador.preparar(base, yaEditado))
                .withMessageContaining("ALREADY_EDITED");
    }
}
