package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.TonoDeMarca;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceRepository;

/** La cuenta raíz: la de la casa, sin límites ni vigencia y con la marca de Pícale. */
class CuentaRaizServiceTest {

    private UserRepository usuarios;
    private OrganizationRepository organizaciones;
    private BrandService marca;
    private CuentaRaizService servicio;

    private Organization organizacion;
    private Workspace espacio;
    private User usuario;

    @BeforeEach
    void preparar() {
        usuarios = mock(UserRepository.class);
        organizaciones = mock(OrganizationRepository.class);
        marca = mock(BrandService.class);
        servicio = new CuentaRaizService(usuarios, organizaciones, marca, "9991557878");

        organizacion = new Organization();
        organizacion.setId(UUID.randomUUID());
        organizacion.setName("Pícale");
        espacio = Workspace.builder().name("Pícale").organization(organizacion).build();
        espacio.setId(UUID.randomUUID());
        usuario = User.builder().name("Equipo Pícale").email("demo@picale.click").workspace(espacio).build();

        when(usuarios.findByEmail("demo@picale.click")).thenReturn(Optional.of(usuario));
        when(organizaciones.findById(organizacion.getId())).thenReturn(Optional.of(organizacion));
    }

    @Test
    @DisplayName("la organización queda sin límites, con el nombre pedido, y se guarda")
    void laVuelveSinLimites() {
        assertThat(organizacion.isSinLimites()).isFalse();

        servicio.convertir("demo@picale.click", " Super Admin ");

        assertThat(organizacion.isSinLimites()).isTrue();
        assertThat(organizacion.getName()).isEqualTo("Super Admin");
        verify(organizaciones).save(organizacion);
    }

    @Test
    @DisplayName("el negocio de la cuenta raíz se llama Pícale, aunque la cuenta haya nacido como demo normal")
    void elNegocioSeLlamaPicale() {
        espacio.setName("PICALE HUB");

        servicio.convertir("demo@picale.click", "PICALE HUB");

        assertThat(espacio.getName()).isEqualTo("Pícale");
    }

    @Test
    @DisplayName("yaEsRaiz: solo cuando su organización ya no tiene límites; una cuenta inexistente, no")
    void yaEsRaiz() {
        assertThat(servicio.yaEsRaiz("demo@picale.click")).isFalse();
        organizacion.setSinLimites(true);
        assertThat(servicio.yaEsRaiz("demo@picale.click")).isTrue();
        assertThat(servicio.yaEsRaiz("otra@picale.click")).isFalse();
    }

    @Test
    @DisplayName("sin nombre de organización se queda con el que tenía")
    void sinNombreConservaElSuyo() {
        servicio.convertir("demo@picale.click", " ");

        assertThat(organizacion.getName()).isEqualTo("Pícale");
        assertThat(organizacion.isSinLimites()).isTrue();
    }

    @Test
    @DisplayName("llena la marca de Pícale en su espacio")
    void llenaLaMarca() {
        servicio.convertir("demo@picale.click", "Super Admin");

        ArgumentCaptor<BrandRequest> pedido = ArgumentCaptor.forClass(BrandRequest.class);
        verify(marca).guardar(any(User.class), pedido.capture());
        assertThat(pedido.getValue().getDescripcion()).contains("Pícale");
        assertThat(pedido.getValue().getCiudad()).isEqualTo("Mérida, Yucatán");
        assertThat(pedido.getValue().getWhatsapp()).isEqualTo("9991557878");
    }

    @Test
    @DisplayName("sin WhatsApp configurado la marca se guarda sin él, sin inventar uno")
    void sinWhatsapp() {
        new CuentaRaizService(usuarios, organizaciones, marca, " ").convertir("demo@picale.click", "PICALE HUB");

        ArgumentCaptor<BrandRequest> pedido = ArgumentCaptor.forClass(BrandRequest.class);
        verify(marca).guardar(any(User.class), pedido.capture());
        assertThat(pedido.getValue().getWhatsapp()).isNull();
    }

    @Test
    @DisplayName("una cuenta que no existe no se inventa")
    void cuentaInexistente() {
        assertThatThrownBy(() -> servicio.convertir("otra@picale.click", "Super Admin"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("la marca respeta los topes de «Mi marca» y con ella el perfil del negocio queda completo")
    void laMarcaCabeYEstaCompleta() {
        BrandRequest m = CuentaRaizService.marcaDePicale();

        assertThat(m.getGiro()).isNotBlank().hasSizeLessThanOrEqualTo(120);
        assertThat(m.getDescripcion()).hasSizeLessThanOrEqualTo(500);
        assertThat(m.getQueVende()).hasSizeLessThanOrEqualTo(400);
        assertThat(m.getPublico()).hasSizeLessThanOrEqualTo(300);
        assertThat(m.getEvitar()).hasSizeLessThanOrEqualTo(200);
        assertThat(m.getTono()).hasSizeLessThanOrEqualTo(TonoDeMarca.MAXIMO);
        assertThat(m.getTono()).allSatisfy(t -> assertThat(TonoDeMarca.de(t)).isPresent());
        assertThat(m.getObjetivo()).isNotNull();
        // Sin precios ni cifras: cambian, y una marca con un precio viejo escribe publicaciones con un precio viejo.
        assertThat(m.getQueVende() + m.getDescripcion()).doesNotContain("$").doesNotContainPattern("\\d");

        // Guardada con el servicio de verdad, el negocio cumple el perfil obligatorio.
        WorkspaceRepository repo = mock(WorkspaceRepository.class);
        when(repo.findById(espacio.getId())).thenReturn(Optional.of(espacio));
        when(repo.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        new BrandService(repo).guardar(usuario, m);

        assertThat(espacio.perfilCompleto()).isTrue();
        assertThat(espacio.getBrandProfile().tono()).hasSize(3);
        assertThat(espacio.getBrandProfile().web()).isEqualTo("https://picale.rodtech.cloud");
    }
}
