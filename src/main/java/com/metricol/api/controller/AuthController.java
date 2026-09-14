package com.metricol.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.models.request.ForgotPasswordRequest;
import com.metricol.api.models.request.GoogleLoginRequest;
import com.metricol.api.models.request.LoginRequest;
import com.metricol.api.models.request.RegisterRequest;
import com.metricol.api.models.request.ResetPasswordRequest;
import com.metricol.api.models.request.VerifyResetCodeRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    /**
     * Entrar con Google.
     *
     * <p>Devuelve exactamente lo mismo que {@code /login}: el JWT de esta
     * aplicación. El token de Google se usa una vez, para saber quién es, y
     * se tira — la sesión que la app guarda sigue siendo la de siempre, así
     * que ni la app ni el resto del backend tienen que enterarse de que
     * existe Google.
     */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> google(
            @Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(authService.loginWithGoogle(request.getIdToken())));
    }

    /**
     * Recuperar el acceso, en tres pasos y tres llamadas: pedir el código,
     * comprobarlo y cambiar la contraseña con él. Los tres van sin sesión
     * —quien llega aquí es justo quien no puede entrar— y los cubre el
     * {@code permitAll} de {@code /auth/**}.
     *
     * <p>Este primero contesta 200 exista el correo o no. La app enseña «si el
     * correo está registrado, te mandamos un código» y no puede decir más,
     * porque el servidor tampoco se lo dice.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** 200 si el código sirve; 400 con el motivo si no. No lo gasta. */
    @PostMapping("/verify-reset-code")
    public ResponseEntity<ApiResponse<Void>> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        authService.verifyResetCode(request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Cambia la contraseña y gasta el código. Después se entra con la nueva por {@code /login}. */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
