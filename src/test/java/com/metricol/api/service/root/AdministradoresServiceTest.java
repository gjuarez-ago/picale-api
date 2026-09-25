package com.metricol.api.service.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.root.AdministradorResponse;
import com.metricol.api.repository.UserRepository;

/** Quién administra la plataforma se gestiona en la base, sin dejarla nunca sin nadie. */
class AdministradoresServiceTest {

    private UserRepository usuarios;
    private AdministradoresService servicio;

    private User raiz;
    private User yo;
    private User otro;

    @BeforeEach
    void preparar() {
        usuarios = mock(UserRepository.class);
        servicio = new AdministradoresService(usuarios, "Demo@Picale.click", true);

        raiz = usuario("demo@picale.click", true);
        yo = usuario("gabriel@picale.test", true);
        otro = usuario("juan@picale.test", false);
        when(usuarios.findById(any())).thenAnswer(i -> {
            UUID id = i.getArgument(0);
            return List.of(raiz, yo, otro).stream().filter(u -> u.getId().equals(id)).findFirst();
        });
    }

    private static User usuario(String correo, boolean admin) {
        return User.builder().id(UUID.randomUUID()).name(correo).email(correo).role(Role.ADMIN)
                .platformAdmin(admin).build();
    }

    @Test
    @DisplayName("la lista marca a la raíz y a quien la ve, con la raíz primero")
    void lista() {
        when(usuarios.findByPlatformAdminTrue()).thenReturn(List.of(yo, raiz));

        List<AdministradorResponse> lista = servicio.listar(yo);

        assertThat(lista).extracting(AdministradorResponse::email)
                .containsExactly("demo@picale.click", "gabriel@picale.test");
        assertThat(lista.get(0).raiz()).isTrue();
        assertThat(lista.get(1).tu()).isTrue();
    }

    @Test
    @DisplayName("agregar da la marca a una cuenta existente, buscando el correo normalizado")
    void agrega() {
        when(usuarios.findByEmail("juan@picale.test")).thenReturn(Optional.of(otro));

        servicio.agregar("  Juan@Picale.test ", yo);

        assertThat(otro.isPlatformAdmin()).isTrue();
        verify(usuarios).save(otro);
    }

    @Test
    @DisplayName("agregar un correo sin cuenta es 404 y no crea nada")
    void agregarSinCuenta() {
        when(usuarios.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.agregar("nadie@picale.test", yo))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("quitar le quita la marca a otra cuenta")
    void quita() {
        otro.setPlatformAdmin(true);

        servicio.quitar(otro.getId(), yo);

        assertThat(otro.isPlatformAdmin()).isFalse();
        verify(usuarios).save(otro);
    }

    @Test
    @DisplayName("nadie se quita a sí mismo, y a la raíz no se le quita")
    void protegidos() {
        assertThatThrownBy(() -> servicio.quitar(yo.getId(), yo))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCode()).isEqualTo("ADMIN_ES_USTED");
        assertThatThrownBy(() -> servicio.quitar(raiz.getId(), yo))
                .isInstanceOf(ConflictoException.class)
                .extracting(e -> ((ConflictoException) e).getCode()).isEqualTo("ADMIN_ES_RAIZ");

        assertThat(yo.isPlatformAdmin()).isTrue();
        assertThat(raiz.isPlatformAdmin()).isTrue();
        verify(usuarios, never()).save(any());
    }

    @Test
    @DisplayName("sin DEMO_ACCOUNT_ROOT la cuenta demo no es raíz y se puede quitar")
    void sinRaiz() {
        AdministradoresService sinRaiz = new AdministradoresService(usuarios, "demo@picale.click", false);

        sinRaiz.quitar(raiz.getId(), yo);

        assertThat(raiz.isPlatformAdmin()).isFalse();
    }
}
