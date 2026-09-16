package com.metricol.api.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Role;
import com.metricol.api.enums.VerificationPurpose;
import com.metricol.api.exception.CodeCooldownException;
import com.metricol.api.exception.GoogleSinCuentaException;
import com.metricol.api.models.request.GoogleRegisterRequest;
import com.metricol.api.models.request.LoginRequest;
import com.metricol.api.models.request.RegisterRequest;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.security.JwtService;
import com.metricol.api.service.auth.EmailService;
import com.metricol.api.service.auth.EmailVerificationService;
import com.metricol.api.service.auth.GoogleTokenVerifier;
import com.metricol.api.util.Correos;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String CORREO_TOMADO = "Ya existe una cuenta con ese correo.";

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final GoogleTokenVerifier googleVerifier;
    private final WorkspaceMemberRepository workspaceMembers;
    private final EmailVerificationService codigos;
    private final EmailService correo;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            GoogleTokenVerifier googleVerifier,
            WorkspaceMemberRepository workspaceMembers,
            EmailVerificationService codigos,
            EmailService correo) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembers = workspaceMembers;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.googleVerifier = googleVerifier;
        this.codigos = codigos;
        this.correo = correo;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = Correos.normalizar(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException(CORREO_TOMADO);
        }

        Workspace workspace = workspaceRepository.save(
                Workspace.builder()
                        .name(request.getWorkspaceName())
                        // Lo que conto de su negocio. Puede venir vacio: los
                        // pasos 2 y 3 del registro se pueden omitir.
                        .giro(request.getGiro())
                        .ciudad(request.getCiudad())
                        .descripcion(request.getDescripcion())
                        .objetivo(request.getObjetivo())
                        .build());

        User user = User.builder()
                .name(request.getName())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.ADMIN)
                .workspace(workspace)
                .build();
        guardarNuevo(user);
        // Quien crea el workspace tiene acceso a él: sin esta fila no podría
        // volver a él después de cambiar a otro cliente.
        workspaceMembers.save(WorkspaceMember.de(user, workspace, Role.ADMIN));

        return sesionPara(user);
    }

    /**
     * Inserta y confirma en el acto, para atrapar la carrera.
     *
     * <p>El {@code existsByEmail} de arriba no basta cuando dos registros del
     * mismo correo entran a la vez: los dos lo pasan. Lo que frena al segundo
     * es la restricción única de la base, y llega como una violación de
     * integridad al escribir. Se fuerza el {@code flush} aquí para que esa
     * violación salte dentro de este método —y no al confirmar la transacción,
     * cuando ya nadie puede traducirla— y se contesta lo mismo que si se
     * hubiera visto a tiempo.
     */
    private void guardarNuevo(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException carrera) {
            throw new IllegalArgumentException(CORREO_TOMADO);
        }
    }

    /**
     * ENTRA con Google. Si no hay cuenta con ese correo, no la crea.
     *
     * <p>Antes la creaba, y eso borraba la diferencia entre entrar y
     * registrarse: cualquiera con una cuenta de Google entraba a una
     * plataforma donde nunca se dio de alta, saltándose el registro entero
     * —nombre del negocio, giro, objetivo— que es justo lo que la IA necesita
     * para escribir. Ahora se contesta {@link GoogleSinCuentaException} y la
     * app lleva al registro con el nombre y el correo ya puestos.
     *
     * <p>Vincular por correo sigue igual y es lo que evita las cuentas dobles:
     * quien se registró con contraseña y luego toca "Entrar con Google" cae en
     * sus publicaciones, no en un workspace vacío que parece que se perdió
     * todo.
     *
     * <p><b>Y por eso se exige que Google haya verificado el correo.</b> Sin
     * esa comprobación, cualquiera podría crear una cuenta de Google con el
     * correo de otra persona, entrar aquí y quedarse con su workspace. Es la
     * única línea de esta clase que sostiene toda la vinculación.
     */
    @Transactional
    public AuthResponse loginWithGoogle(String idToken) {
        String email = correoVerificadoDe(idToken);

        return userRepository.findByEmail(email)
                .map(this::sesionPara)
                .orElseThrow(() -> new GoogleSinCuentaException(
                        "No hay una cuenta de Pícale con ese correo. Crea tu cuenta para empezar."));
    }

    /**
     * REGISTRA con Google, o entra si ya existe.
     *
     * <p>Lo segundo no es una puerta trasera al registro: en esta pantalla la
     * persona está pidiendo darse de alta, y descubrir que ya tenía cuenta no
     * es un error que valga la pena contarle — es la misma identidad, ya
     * verificada por Google, así que entra.
     *
     * <p>Sin contraseña que elegir: la cuenta nace con una aleatoria que nadie
     * conoce. Para entrar con correo y contraseña hay que pasar por «olvidé mi
     * contraseña», que es exactamente el camino correcto — lo pide quien lee
     * ese correo.
     */
    @Transactional
    public AuthResponse registerWithGoogle(GoogleRegisterRequest request) {
        GoogleTokenVerifier.Identidad identidad = googleVerifier.verificar(request.getIdToken());
        exigirCorreoVerificado(identidad);

        String email = Correos.normalizar(identidad.email());
        User existente = userRepository.findByEmail(email).orElse(null);
        if (existente != null) {
            return sesionPara(existente);
        }

        String nombre = primeroNoVacio(
                request.getName(),
                identidad.nombre(),
                email.split("@")[0]);
        String negocio = primeroNoVacio(request.getWorkspaceName(), nombre);

        Workspace workspace = workspaceRepository.save(
                Workspace.builder().name(negocio).build());

        User nuevo = User.builder()
                .name(nombre)
                .email(email)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(Role.ADMIN)
                .workspace(workspace)
                .build();
        guardarNuevo(nuevo);
        workspaceMembers.save(WorkspaceMember.de(nuevo, workspace, Role.ADMIN));

        log.info("Cuenta creada desde Google: {}", email);
        return sesionPara(nuevo);
    }

    /** El correo del token, comprobado y normalizado. */
    private String correoVerificadoDe(String idToken) {
        GoogleTokenVerifier.Identidad identidad = googleVerifier.verificar(idToken);
        exigirCorreoVerificado(identidad);
        return Correos.normalizar(identidad.email());
    }

    private void exigirCorreoVerificado(GoogleTokenVerifier.Identidad identidad) {
        if (!identidad.emailVerificado()) {
            throw new IllegalArgumentException(
                    "Google no ha verificado ese correo. Verifícalo en tu cuenta de Google e intenta de nuevo.");
        }
    }

    private static String primeroNoVacio(String... candidatos) {
        for (String candidato : candidatos) {
            if (candidato != null && !candidato.isBlank()) {
                return candidato.strip();
            }
        }
        return "";
    }

    public AuthResponse login(LoginRequest request) {
        String email = Correos.normalizar(request.getEmail());

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword()));

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Correo o contraseña incorrectos."));

        return sesionPara(user);
    }

    /**
     * «Olvidé mi contraseña»: manda un código de seis dígitos al correo.
     *
     * <p>Contesta lo mismo exista el correo o no, y por eso aquí se calla todo:
     * si el correo no está, no pasa nada; si se pidió otro código hace un
     * momento, tampoco. Cualquier diferencia en la respuesta convertiría esta
     * pantalla en una forma de averiguar quién tiene cuenta. El código que ya
     * llegó a la bandeja sigue sirviendo.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = Correos.normalizar(rawEmail);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }

        String codigo;
        try {
            codigo = codigos.emitir(email, VerificationPurpose.PASSWORD_RESET);
        } catch (CodeCooldownException frenado) {
            return;
        }

        correo.enviarCodigoDeRecuperacion(
                email, user.getName(), codigo, EmailVerificationService.PASSWORD_RESET_TTL_MINUTES);
    }

    /** Comprueba el código sin gastarlo, para que la app pueda pasar al siguiente paso. */
    @Transactional
    public void verifyResetCode(String rawEmail, String codigo) {
        codigos.comprobar(rawEmail, codigo, VerificationPurpose.PASSWORD_RESET);
    }

    /**
     * Cambia la contraseña con el código. El código se gasta primero: si por
     * lo que sea el usuario no estuviera, el código tampoco vuelve a servir.
     */
    @Transactional
    public void resetPassword(String rawEmail, String codigo, String contrasenaNueva) {
        String email = Correos.normalizar(rawEmail);

        codigos.consumir(email, codigo, VerificationPurpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(EmailVerificationService.CODIGO_INVALIDO));

        user.setPassword(passwordEncoder.encode(contrasenaNueva));
        userRepository.save(user);

        log.info("Contraseña restablecida para {}", email);
    }

    /**
     * La sesión de un usuario en su workspace activo: token y datos.
     *
     * <p>Pública porque también la emite el cambio de workspace
     * (WorkspaceMembershipService.activar), y las dos tienen que ser idénticas:
     * la app guarda la de cambiar de cliente igual que la de entrar.
     */
    public AuthResponse sesionPara(User user) {
        String token = jwtService.generateToken(
                Map.of(
                        "workspaceId", user.getWorkspace().getId().toString(),
                        "role", user.getRole().name()),
                user);

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .workspaceId(user.getWorkspace().getId())
                .workspaceName(user.getWorkspace().getName())
                .build();
    }
}
