package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.BrandService;

/** La voz de la marca: historia, valores, frases y pilares, guardados y en los prompts. */
class VozDeLaMarcaTest {

    @Test
    @DisplayName("se guarda limpia: frases una por línea, pilares solo los que existen")
    void guarda() {
        Workspace w = Workspace.builder().name("CMRG").giro("Construcción").descripcion("Obras").build();
        w.setId(UUID.randomUUID());
        WorkspaceRepository repo = mock(WorkspaceRepository.class);
        when(repo.findById(w.getId())).thenReturn(Optional.of(w));
        when(repo.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        User u = mock(User.class);
        when(u.getWorkspace()).thenReturn(w);

        BrandRequest p = new BrandRequest();
        p.setHistoria("  Empezamos con una cuadrilla de tres.  ");
        p.setValores("Puntualidad, trabajo bien hecho");
        p.setFrases("Lo que se construye con paciencia dura generaciones.\n\n  Cada obra lleva nuestro nombre. ");
        p.setPilares(List.of("motivacion", "HISTORIA_DUENO", "INVENTADO"));
        var r = new BrandService(repo).guardar(u, p);

        assertThat(r.historia()).isEqualTo("Empezamos con una cuadrilla de tres.");
        assertThat(r.frases()).isEqualTo("Lo que se construye con paciencia dura generaciones.\nCada obra lleva nuestro nombre.");
        assertThat(r.pilares()).containsExactly("MOTIVACION", "HISTORIA_DUENO");

        // Quien no manda la voz de la marca (la app instalada) no la borra.
        BrandRequest viejo = new BrandRequest();
        viejo.setQueVende("Obras");
        assertThat(new BrandService(repo).guardar(u, viejo).pilares()).containsExactly("MOTIVACION", "HISTORIA_DUENO");

        MarcaDelNegocio m = MarcaDelNegocio.delEspacio(w);
        assertThat(m.conVidaPersonal()).isTrue();
        assertThat(m.pilaresEs()).contains("motivar a su publico");
    }

    @Test
    @DisplayName("la sugerencia de la IA se lee: frases cortas y pilares válidos")
    void sugerencia() throws Exception {
        var s = new SugerenciaDeMarca(null).interpretar("""
                {"historia": "Construimos espacios que duran.", "valores": "Puntualidad, honestidad",
                 "frases": ["Lo que se construye con paciencia dura generaciones.", "", "Cada obra, nuestra firma."],
                 "pilares": ["MOTIVACION", "CONSEJOS", "NO_EXISTE"]}""");
        assertThat(s.frases()).hasSize(2);
        assertThat(s.pilares()).containsExactly("MOTIVACION", "CONSEJOS");
    }
}
