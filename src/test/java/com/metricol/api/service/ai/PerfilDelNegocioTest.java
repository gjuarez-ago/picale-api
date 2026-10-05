package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.BrandService;

/** Cómo trabaja el negocio: se deduce, se guarda, el dueño lo corrige y filtra lo que aplica. */
class PerfilDelNegocioTest {

    @Test
    @DisplayName("lee los rasgos de la IA y descarta los que no existen")
    void interpretar() throws Exception {
        Set<RasgoDelNegocio> r = new PerfiladorDelNegocio(null)
                .interpretar("{\"rasgos\": [\"POR_PROYECTO\", \"cotiza\", \"INVENTADO\", \"ATIENDE_ZONA\"]}");
        assertThat(r).containsExactlyInAnyOrder(RasgoDelNegocio.POR_PROYECTO, RasgoDelNegocio.COTIZA,
                RasgoDelNegocio.ATIENDE_ZONA);
        assertThat(RasgoDelNegocio.guardar(r)).isEqualTo("POR_PROYECTO,COTIZA,ATIENDE_ZONA");
        assertThat(RasgoDelNegocio.de((String) null)).isNull();
        assertThat(RasgoDelNegocio.de("")).isEmpty();
    }

    @Test
    @DisplayName("los prompts reciben lo que cambia por cada rasgo: cotizar, nunca inventar precios")
    void enLosPrompts() {
        Workspace w = Workspace.builder().name("CMRG").giro("Construcción").build();
        w.setPerfilRasgos("POR_PROYECTO,COTIZA");
        MarcaDelNegocio m = MarcaDelNegocio.delEspacio(w);
        assertThat(m.tiene(RasgoDelNegocio.COTIZA)).isTrue();
        assertThat(m.comoTrabajaEs()).contains("nunca escribas ni inventes precios").contains("portafolio");
        assertThat(MarcaDelNegocio.delEspacio(Workspace.builder().name("x").build()).comoTrabajaEs()).isEmpty();
    }

    private static BrandService servicio(Workspace w) {
        WorkspaceRepository repo = mock(WorkspaceRepository.class);
        when(repo.findById(w.getId())).thenReturn(Optional.of(w));
        when(repo.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        return new BrandService(repo);
    }

    private static User de(Workspace w) {
        User u = mock(User.class);
        when(u.getWorkspace()).thenReturn(w);
        return u;
    }

    @Test
    @DisplayName("el dueño elige sus rasgos en Marca y desde ahí mandan los suyos")
    void delDueno() {
        Workspace w = Workspace.builder().name("CMRG").giro("Construcción").descripcion("Obras").build();
        w.setId(UUID.randomUUID());
        w.setPerfilRasgos("COTIZA");
        BrandRequest p = new BrandRequest();
        p.setRasgos(List.of("POR_PROYECTO", "ATIENDE_ZONA"));
        var r = servicio(w).guardar(de(w), p);
        assertThat(r.rasgos()).containsExactly("POR_PROYECTO", "ATIENDE_ZONA");
        assertThat(r.rasgosDelDueno()).isTrue();

        // Ya son suyos: cambiar el giro no los borra.
        BrandRequest otro = new BrandRequest();
        otro.setGiro("Remodelación");
        assertThat(servicio(w).guardar(de(w), otro).rasgos()).containsExactly("POR_PROYECTO", "ATIENDE_ZONA");
    }

    @Test
    @DisplayName("si cambia a qué se dedica, lo que dedujo la IA se vuelve a deducir")
    void cambiaElGiro() {
        Workspace w = Workspace.builder().name("Tacos").giro("Taquería").descripcion("Tacos").build();
        w.setId(UUID.randomUUID());
        w.setPerfilRasgos("PRODUCTO,LOCAL");
        BrandRequest p = new BrandRequest();
        p.setGiro("Banquetes");
        assertThat(servicio(w).guardar(de(w), p).rasgos()).isNull();
    }
}
