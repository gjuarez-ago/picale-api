package com.metricol.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.metricol.api.repository.UserRepository;
import com.metricol.api.util.Correos;

@Configuration
@EnableAsync
@EnableScheduling
public class ApplicationConfig {

    private final UserRepository userRepository;

    public ApplicationConfig(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Busca por el correo ya normalizado. Por aquí pasan tanto el login como
     * el JWT de cada petición —su {@code sub} es el correo—, así que es el
     * sitio donde una mayúscula de más dejaría fuera a alguien que sí existe.
     *
     * <p>Con el workspace cargado: este usuario vive toda la petición como
     * principal, fuera de cualquier sesión de Hibernate, y lo que no venga
     * cargado aquí no se puede leer después. Ver {@code findByEmailConWorkspace}.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return email -> userRepository.findByEmailConWorkspace(Correos.normalizar(email))
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
