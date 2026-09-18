package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.metricol.api.exception.GoogleSinCuentaException;
import com.metricol.api.models.request.GoogleRegisterRequest;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.auth.GoogleTokenVerifier;

/**
 * Entrar y registrarse con Google, que dejaron de ser lo mismo.
 *
 * <p>Es la separación que impedía decidir quién entra: {@code /auth/google}
 * creaba la cuenta si no la encontraba, así que cualquiera con una cuenta de
 * Google entraba a producción sin haberse registrado nunca y sin pasar por el
 * formulario del que sale el contexto del negocio.
 *
 * <p>El verificador va doblado: comprobar un token de verdad exigiría uno
 * emitido por Google, y lo que se prueba aquí no es la criptografía —eso es
 * suyo— sino qué hace cada camino con una identidad ya verificada.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000"
})
class AuthServiceGoogleTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private GoogleTokenVerifier googleVerifier;

    private String correo;

    @BeforeEach
    void identidadDeGoogle() {
        correo = "google-" + UUID.randomUUID() + "@ejemplo.test";
        when(googleVerifier.verificar(anyString()))
                .thenReturn(new GoogleTokenVerifier.Identidad("sub-123", correo, "Ana Pérez", true));
    }

    @Test
    void entrarSinCuentaNoLaCreaYLoDice() {
        assertThatThrownBy(() -> authService.loginWithGoogle("token"))
                .isInstanceOf(GoogleSinCuentaException.class);

        // Lo que de verdad importa: no quedó nada creado por haberlo intentado.
        assertThat(userRepository.findByEmail(correo)).isEmpty();
    }

    @Test
    void registrarseConGoogleCreaLaCuentaYEntra() {
        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");
        peticion.setName("Ana Pérez");
        peticion.setWorkspaceName("Tacos de Ana");

        AuthResponse sesion = authService.registerWithGoogle(peticion);

        assertThat(sesion.getToken()).isNotBlank();
        assertThat(sesion.getEmail()).isEqualTo(correo);
        assertThat(sesion.getWorkspaceName()).isEqualTo("Tacos de Ana");
        assertThat(userRepository.findByEmail(correo)).isPresent();
    }

    @Test
    void sinNombreDeNegocioElEspacioNaceSinNombre() {
        // No con el de la persona, que es lo que hacía antes. El nombre del
        // negocio se pregunta en el paso siguiente del registro, así que en
        // este momento no se sabe; ponerle el de quien abre la cuenta dejaba
        // el perfil enseñando el mismo nombre dos veces, y para siempre si se
        // omitía ese paso.
        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");
        peticion.setName("Ana Pérez");

        AuthResponse sesion = authService.registerWithGoogle(peticion);

        assertThat(sesion.getName()).isEqualTo("Ana Pérez");
        assertThat(sesion.getWorkspaceName()).isNull();
    }

    @Test
    void unNombreDeNegocioEnBlancoCuentaComoNoDarlo() {
        // Guardar la cadena vacía se vería igual que un nombre puesto, y
        // después no habría forma de saber que falta.
        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");
        peticion.setName("Ana Pérez");
        peticion.setWorkspaceName("   ");

        assertThat(authService.registerWithGoogle(peticion).getWorkspaceName())
                .isNull();
    }

    @Test
    void despuesDeRegistrarse_entrarConGoogleYaFunciona() {
        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");
        authService.registerWithGoogle(peticion);

        AuthResponse sesion = authService.loginWithGoogle("token");

        assertThat(sesion.getEmail()).isEqualTo(correo);
    }

    @Test
    void registrarseDosVecesNoDuplicaLaCuenta() {
        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");

        AuthResponse primera = authService.registerWithGoogle(peticion);
        AuthResponse segunda = authService.registerWithGoogle(peticion);

        // La segunda entra en la misma cuenta en vez de fallar: es la misma
        // identidad verificada por Google, y decirle "ya existe" a quien
        // acaba de tocar "continuar con Google" seria un error de mas.
        assertThat(segunda.getUserId()).isEqualTo(primera.getUserId());
    }

    @Test
    void sinCorreoVerificadoNoSeRegistraNiEntra() {
        when(googleVerifier.verificar(anyString()))
                .thenReturn(new GoogleTokenVerifier.Identidad("sub-123", correo, "Ana", false));

        GoogleRegisterRequest peticion = new GoogleRegisterRequest();
        peticion.setIdToken("token");

        // Es la linea que sostiene la vinculacion por correo: sin ella,
        // cualquiera con una cuenta de Google a nombre de otro entraria en su
        // workspace.
        assertThatThrownBy(() -> authService.registerWithGoogle(peticion))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authService.loginWithGoogle("token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(userRepository.findByEmail(correo)).isEmpty();
    }
}
