package com.metricol.api.service.auth;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.EmailVerificationCode;
import com.metricol.api.enums.VerificationPurpose;
import com.metricol.api.exception.CodeCooldownException;
import com.metricol.api.repository.EmailVerificationCodeRepository;
import com.metricol.api.util.Correos;

/**
 * Los códigos de seis dígitos que se mandan al correo.
 *
 * <p>Es el mismo esquema que vivento366: el código vive en la tabla solo como
 * hash BCrypt, dura media hora, admite cinco intentos y no se puede pedir otro
 * antes de 45 segundos. Emitir y enviar están separados a propósito: aquí se
 * fabrica y se comprueba; quién lo manda y con qué correo es de quien llama.
 *
 * <p><b>Un solo mensaje para todo lo que no es válido.</b> Distinguir «no hay
 * código» de «el código no coincide» le diría a quien está probando correos
 * cuáles existen: solo un correo registrado puede tener un código que no
 * coincida. Por eso vencido, inexistente y equivocado contestan igual.
 */
@Service
public class EmailVerificationService {

    /**
     * Media hora y no diez minutos. Quien perdió su contraseña muchas veces
     * pide el código, deja el teléfono y vuelve; media hora es lo que separa
     * «llegó tarde» de «hay que pedir otro».
     */
    public static final int PASSWORD_RESET_TTL_MINUTES = 30;

    /** Cinco sobre un millón de combinaciones: la fuerza bruta no llega a nada. */
    static final int MAX_INTENTOS = 5;

    /** Sin esto, el botón «reenviar» es un generador de correo basura contra terceros. */
    static final int COOLDOWN_SEGUNDOS = 45;

    public static final String CODIGO_INVALIDO = "El código no coincide o ya no es válido. Pide uno nuevo.";

    private final EmailVerificationCodeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationCodeRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Emite un código nuevo, invalida los anteriores de ese correo y propósito
     * —para que dos pantallas abiertas no dejen dos válidos— y lo devuelve
     * <b>en claro</b>, que es la única vez que existe así.
     *
     * @throws CodeCooldownException si se pidió otro hace menos de
     *         {@value #COOLDOWN_SEGUNDOS} segundos. Va aparte porque en el
     *         restablecimiento frenar es correcto pero decirlo delataría que
     *         el correo existe.
     */
    @Transactional
    public String emitir(String rawEmail, VerificationPurpose purpose) {
        String email = exigirCorreo(rawEmail);

        Optional<EmailVerificationCode> ultimo = repository
                .findTopByEmailAndPurposeAndConsumedFalseOrderByCreatedAtDesc(email, purpose);
        if (ultimo.isPresent() && ultimo.get().getCreatedAt() != null
                && ultimo.get().getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(COOLDOWN_SEGUNDOS))) {
            throw new CodeCooldownException("Espera unos segundos antes de pedir otro código.");
        }

        repository.consumirTodos(email, purpose);

        String codigo = generar();
        repository.save(EmailVerificationCode.builder()
                .email(email)
                .purpose(purpose)
                .codeHash(passwordEncoder.encode(codigo))
                .expiresAt(LocalDateTime.now().plusMinutes(ttlMinutos(purpose)))
                .build());

        return codigo;
    }

    /**
     * Comprueba el código sin gastarlo. Es lo que permite que la app avance a
     * «elige tu contraseña» con la certeza de que el código sirve, en vez de
     * descubrir que no al final.
     */
    @Transactional
    public void comprobar(String rawEmail, String codigo, VerificationPurpose purpose) {
        vigente(rawEmail, codigo, purpose);
    }

    /** Comprueba el código y lo da por usado. */
    @Transactional
    public void consumir(String rawEmail, String codigo, VerificationPurpose purpose) {
        EmailVerificationCode registro = vigente(rawEmail, codigo, purpose);
        registro.setConsumed(true);
        repository.save(registro);
    }

    private EmailVerificationCode vigente(String rawEmail, String codigo, VerificationPurpose purpose) {
        String email = exigirCorreo(rawEmail);

        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("Escribe el código que te enviamos por correo.");
        }

        EmailVerificationCode registro = repository
                .findTopByEmailAndPurposeAndConsumedFalseOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new IllegalArgumentException(CODIGO_INVALIDO));

        if (registro.isExpired() || registro.getAttempts() >= MAX_INTENTOS) {
            registro.setConsumed(true);
            repository.save(registro);
            throw new IllegalArgumentException(CODIGO_INVALIDO);
        }

        if (!passwordEncoder.matches(codigo.strip(), registro.getCodeHash())) {
            registro.setAttempts(registro.getAttempts() + 1);
            repository.save(registro);
            throw new IllegalArgumentException(CODIGO_INVALIDO);
        }

        return registro;
    }

    private int ttlMinutos(VerificationPurpose purpose) {
        return PASSWORD_RESET_TTL_MINUTES;
    }

    private String exigirCorreo(String rawEmail) {
        String email = Correos.normalizar(rawEmail);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("El correo es obligatorio.");
        }
        return email;
    }

    /** Seis dígitos con ceros a la izquierda: 000000 es tan válido como 999999. */
    private String generar() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
