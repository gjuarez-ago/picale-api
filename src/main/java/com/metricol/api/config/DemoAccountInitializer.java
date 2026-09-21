package com.metricol.api.config;

import com.metricol.api.service.CuentaRaizService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.metricol.api.models.request.RegisterRequest;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.AuthService;
import com.metricol.api.util.Correos;

/**
 * Crea al arrancar la cuenta de demostración que usa Google Play para revisar
 * la app.
 *
 * <p><b>Los datos no están en el código, y es a propósito.</b> Correo y
 * contraseña salen de variables de entorno ({@code DEMO_ACCOUNT_EMAIL},
 * {@code DEMO_ACCOUNT_PASSWORD}) que solo existen en el servidor. Este
 * repositorio puede ser público: una contraseña escrita aquí sería la de una
 * cuenta real de producción, con acceso al panel y a la IA —que cuesta por
 * uso— para cualquiera que lea el código.
 *
 * <p>Sin las dos variables no hace nada. Así, en desarrollo y en cualquier
 * servidor donde no se configuren, no aparece ninguna cuenta sorpresa.
 *
 * <p>Pasa por el mismo {@link AuthService#register} que una persona que se
 * registra, no por un atajo: la cuenta nace con su espacio de trabajo, su
 * organización y ella de dueña, exactamente como la vería el revisor si la
 * hubiera creado desde la app. Un atajo que insertara filas a mano se
 * desincronizaría del registro real el día que este cambie.
 *
 * <p><b>Cuenta raíz.</b> Con {@code DEMO_ACCOUNT_ROOT=true} la cuenta nace como la de la casa: su
 * negocio es Pícale, con la marca de Pícale llena, y su organización queda sin límites ni vigencia
 * (ver {@link CuentaRaizService}). Se pone en el servidor, junto al correo y la contraseña; nunca se
 * activa sola.
 *
 * <p>Si la cuenta ya existe, no se toca —ni siquiera la contraseña—. Correr
 * este arranque cien veces deja lo mismo que correrlo una. Para cambiar la
 * contraseña se usa "olvidé mi contraseña", como cualquiera.
 */
@Configuration
public class DemoAccountInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountInitializer.class);

    @Bean
    @Order(30)
    public ApplicationRunner crearCuentaDeDemostracion(
            AuthService auth,
            CuentaRaizService raiz,
            UserRepository usuarios,
            @Value("${app.demo.email:}") String email,
            @Value("${app.demo.password:}") String password,
            @Value("${app.demo.organization:Picale HUB}") String organizacion,
            @Value("${app.demo.root:false}") boolean esRaiz) {

        return args -> {
            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                return;
            }

            String correo = Correos.normalizar(email);
            if (usuarios.existsByEmail(correo)) {
                log.info("La cuenta de demostracion {} ya existe; no se toca.", correo);
                return;
            }

            try {
                RegisterRequest registro = new RegisterRequest();
                // El nombre y el espacio con el de la organización: es lo que
                // la hace nacer con ese nombre (ver AuthService.register).
                // La cuenta raíz es la de la propia empresa: su negocio se llama Pícale y la
                // organización (la variable) se le pone después, al volverla raíz.
                registro.setName(esRaiz ? "Equipo Pícale" : organizacion);
                registro.setWorkspaceName(esRaiz ? "Pícale" : organizacion);
                registro.setEmail(correo);
                registro.setPassword(password);

                auth.register(registro);
                if (esRaiz) {
                    raiz.convertir(correo, organizacion);
                }
                // Sin la contraseña en el registro, nunca: los registros
                // acaban en consolas y en herramientas de terceros.
                log.info("Cuenta de demostracion creada: {} en la organizacion \"{}\"{}.", correo, organizacion,
                        esRaiz ? " (raiz: sin limites ni vigencia, con la marca de Picale)" : "");
            } catch (Exception ex) {
                // No impide arrancar: sin la cuenta demo la API sirve igual a
                // todos los demás. Se ve en el registro y se corrige.
                log.error("No se pudo crear la cuenta de demostracion {}: {}", correo, ex.getMessage());
            }
        };
    }
}
