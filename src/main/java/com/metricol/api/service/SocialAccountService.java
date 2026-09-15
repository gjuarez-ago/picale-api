package com.metricol.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.SocialAccount;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.SocialAccountConnectRequest;
import com.metricol.api.models.response.SocialAccountResponse;
import com.metricol.api.repository.SocialAccountRepository;

@Service
public class SocialAccountService {

    private final SocialAccountRepository repository;

    public SocialAccountService(SocialAccountRepository repository) {
        this.repository = repository;
    }

    public List<SocialAccountResponse> list() {
        return repository.findAllByOrderByConnectedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    public SocialAccountResponse connect(SocialAccountConnectRequest request) {
        SocialAccount account = SocialAccount.builder()
                .platform(request.getPlatform())
                .accountName(request.getAccountName())
                .status(SocialAccountStatus.CONNECTED)
                .connectedAt(LocalDateTime.now())
                .build();

        return toResponse(repository.save(account));
    }

    public void disconnect(UUID id) {
        SocialAccount account = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cuenta social no encontrada."));
        repository.delete(account);
    }

    /**
     * Apaga una red entera para este workspace.
     *
     * <p>Es lo que hay detrás del botón "Desconectar", y va por plataforma y
     * no por id porque es lo que la pantalla enseña: una fila por red, no una
     * por fila de la tabla.
     *
     * <p><b>No revoca nada en el proveedor.</b> Los tokens viven en
     * upload-post y nuestro cliente no conoce ningún endpoint suyo para
     * revocarlos, así que lo que se hace es dejar de usarla: se marca apagada
     * y {@code SocialAccountSyncService} respeta esa marca aunque upload-post
     * siga reportándola conectada. Deja de salir donde se publica, que es lo
     * que se pide al tocar el botón.
     *
     * <p>Se conserva la fila en vez de borrarla, igual que cuando se
     * desconecta desde fuera: las publicaciones que ya salieron por ahí
     * apuntan a ella.
     */
    @Transactional
    public void disconnectPlatform(Platform platform) {
        List<SocialAccount> suyas = repository.findAllByOrderByConnectedAtDesc().stream()
                .filter(cuenta -> cuenta.getPlatform() == platform)
                .toList();

        if (suyas.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No hay ninguna cuenta de " + platform.getLabel() + " conectada.");
        }

        for (SocialAccount cuenta : suyas) {
            cuenta.setStatus(SocialAccountStatus.DISCONNECTED);
            cuenta.setDesactivadaPorUsuario(true);
            repository.save(cuenta);
        }
    }

    /**
     * Levanta la marca de apagado de una red, para que vuelva a sincronizarse.
     *
     * <p>Se llama al empezar a conectarla otra vez: es la única señal fiable
     * de que se cambió de opinión. Sin esto, una red apagada quedaría apagada
     * para siempre — reconectarla no serviría, porque el sync la seguiría
     * saltando.
     */
    @Transactional
    public void reactivarPlatform(Platform platform) {
        repository.findAllByOrderByConnectedAtDesc().stream()
                .filter(cuenta -> cuenta.getPlatform() == platform && cuenta.apagadaPorLaPersona())
                .forEach(cuenta -> {
                    cuenta.setDesactivadaPorUsuario(false);
                    repository.save(cuenta);
                });
    }

    private SocialAccountResponse toResponse(SocialAccount account) {
        return SocialAccountResponse.builder()
                .id(account.getId())
                .platform(account.getPlatform())
                .accountName(account.getAccountName())
                .avatarUrl(account.getAvatarUrl())
                .status(account.getStatus())
                .connectedAt(account.getConnectedAt())
                .pageId(account.getPageId())
                .needsPage(account.sinPagina())
                .build();
    }
}
