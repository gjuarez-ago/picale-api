package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

import com.metricol.api.models.request.RegisterRequest;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.AuthService;

/**
 * La cuenta de demostración corre en cada arranque de producción, así que se
 * prueba lo que no puede fallar: que sin configurar no invente nada, que no
 * pise una cuenta que ya existe, y que nazca con el nombre de la organización.
 */
class DemoAccountInitializerTest {

    private final AuthService auth = mock(AuthService.class);
    private final UserRepository usuarios = mock(UserRepository.class);

    @Test
    @DisplayName("Sin correo o sin contraseña no crea nada")
    void sinConfigurarNoHaceNada() throws Exception {
        correr("", "algo");
        correr("demo@picale.click", "");

        verify(auth, never()).register(any());
    }

    @Test
    @DisplayName("Si la cuenta ya existe no la toca, ni su contraseña")
    void noPisaUnaCuentaExistente() throws Exception {
        when(usuarios.existsByEmail("demo@picale.click")).thenReturn(true);

        correr("demo@picale.click", "otra-clave");

        verify(auth, never()).register(any());
    }

    @Test
    @DisplayName("La crea con el nombre de la organización, que es lo que la nombra")
    void laCreaConLaOrganizacion() throws Exception {
        when(usuarios.existsByEmail("demo@picale.click")).thenReturn(false);

        correr("Demo@Picale.Click", "clave-segura");

        ArgumentCaptor<RegisterRequest> registro = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(auth).register(registro.capture());
        assertThat(registro.getValue().getEmail()).isEqualTo("demo@picale.click");
        assertThat(registro.getValue().getName()).isEqualTo("Picale HUB");
        assertThat(registro.getValue().getWorkspaceName()).isEqualTo("Picale HUB");
    }

    @Test
    @DisplayName("Si falla el registro, la API arranca igual")
    void unFalloNoTumbaElArranque() throws Exception {
        when(usuarios.existsByEmail(any())).thenReturn(false);
        when(auth.register(any())).thenThrow(new IllegalArgumentException("algo salió mal"));

        correr("demo@picale.click", "clave-segura");
        // Llegar aquí sin excepción es la prueba.
    }

    private void correr(String email, String password) throws Exception {
        ApplicationRunner runner = new DemoAccountInitializer()
                .crearCuentaDeDemostracion(auth, usuarios, email, password, "Picale HUB");
        runner.run(new DefaultApplicationArguments());
    }
}
