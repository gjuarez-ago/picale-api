package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;

class TituloDelReelTest {

    private static AgenteService.EnTanda foto(boolean esArte) {
        DecisorDelAgente.Diagnostico d = new DecisorDelAgente.Diagnostico(4, "", true, 4, esArte, false,
                DecisorDelAgente.Intencion.INFORMAR, "OTRO");
        return new AgenteService.EnTanda(new MediaAsset(),
                new RevisorDeMarca.Revision(RevisorDeMarca.Veredicto.VA, "", "", "", d), false);
    }

    @Test
    @DisplayName("Sobre fotos sin texto va el título; con una lámina ya diseñada, no (le tapaba el encabezado)")
    void soloSobreFotosSinTexto() {
        assertThat(AgenteService.tituloDelReel(List.of(foto(false), foto(false)), " Cómo estructurar un holding "))
                .isEqualTo("Cómo estructurar un holding");
        assertThat(AgenteService.tituloDelReel(List.of(foto(false), foto(true)), "Cómo estructurar un holding"))
                .isNull();
        assertThat(AgenteService.tituloDelReel(List.of(foto(false)), " ")).isNull();
        assertThat(AgenteService.tituloDelReel(List.of(new AgenteService.EnTanda(new MediaAsset(), null, false)), "Tema"))
                .isEqualTo("Tema");
    }
}
