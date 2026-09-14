package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Role;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Deja lista una cuenta con la que entrar en desarrollo.
 *
 * <p>El perfil dev corre sobre H2 en memoria: la base se borra en cada
 * arranque, así que sin esto había que registrar un usuario a mano —y
 * acordarse del correo y la contraseña— cada vez que se levanta el API.
 *
 * <p><b>{@code @Profile("dev")} no es decorativo.</b> Esta cuenta nace con una
 * contraseña trivial y rol ADMIN; en qa o producción sería una puerta abierta.
 * La anotación es lo único que lo impide, así que no se le quita ni se
 * "generaliza" a otros perfiles: si algún día hace falta sembrar en qa, va con
 * credenciales por variable de entorno y sin default.
 *
 * <p>Siembra solo si el correo no existe. Así, apuntar el perfil dev a un
 * Postgres local (ver {@code DB_*} en application-dev.yml) no repite la cuenta
 * ni le pisa la contraseña a quien ya la haya cambiado.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMembers;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.dev-seed.email:demo@pulso.test}")
    private String email;

    @Value("${app.dev-seed.password:123456}")
    private String password;

    @Value("${app.dev-seed.name:Demo}")
    private String name;

    @Value("${app.dev-seed.workspace-name:Picale}")
    private String workspaceName;

    /**
     * El nombre del perfil en upload-post, FIJO a propósito.
     *
     * <p>Sin esto, {@code UploadPostConnectService.profileOf} cae al UUID del
     * workspace — y aquí ese UUID es nuevo en cada arranque, porque H2 vive en
     * memoria. El efecto era que conectabas Facebook, reiniciabas el API y la
     * app decía que no había ninguna red conectada: seguían conectadas en
     * upload-post, pero colgando de un perfil que ya nadie preguntaba.
     *
     * <p>Un nombre fijo hace que las conexiones sobrevivan a los reinicios. En
     * qa y producción no hace falta: allá la base persiste y el UUID es
     * estable, que es justo lo que ese fallback supone.
     */
    @Value("${app.dev-seed.upload-post-profile:pulso-dev}")
    private String uploadPostProfile;

    public DevDataSeeder(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMembers,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembers = workspaceMembers;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByEmail(email)) {
            log.info("Usuario de desarrollo: {} (ya existía, no se tocó)", email);
            return;
        }

        Workspace workspace = workspaceRepository.save(
                Workspace.builder()
                        .name(workspaceName)
                        .uploadPostProfile(uploadPostProfile)
                        .build());

        // Mismo armado que AuthService.register: si aquí se guardara la
        // contraseña sin codificar, la cuenta se crearía pero el login
        // fallaría con "correo o contraseña incorrectos" sin decir por qué.
        User usuario = userRepository.save(User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .workspace(workspace)
                .build());
        workspaceMembers.save(WorkspaceMember.de(usuario, workspace, Role.ADMIN));

        // Se imprime la contraseña a propósito: es el punto de todo esto, y
        // solo ocurre en dev, donde es pública por diseño.
        log.info("Usuario de desarrollo listo -> {} / {}", email, password);
        log.info("Perfil de upload-post: {} (fijo, para que las redes conectadas "
                + "sobrevivan al reinicio)", uploadPostProfile);
    }
}
