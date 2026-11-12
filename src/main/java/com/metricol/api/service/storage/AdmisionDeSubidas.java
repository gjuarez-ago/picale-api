package com.metricol.api.service.storage;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.metricol.api.entity.UploadScope;
import com.metricol.api.repository.UploadScopeRepository;

@Service
public class AdmisionDeSubidas {
    private final UploadScopeRepository scopes;
    private final TransactionTemplate tx;
    public AdmisionDeSubidas(UploadScopeRepository scopes, PlatformTransactionManager manager) {
        this.scopes = scopes;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T> T ejecutar(UUID workspace, Supplier<T> operacion) {
        if (!scopes.existsById(workspace)) {
            try { tx.executeWithoutResult(s -> scopes.saveAndFlush(new UploadScope(workspace))); }
            catch (DataIntegrityViolationException ex) {
                if (!scopes.existsById(workspace)) throw ex;
            }
        }
        return tx.execute(s -> {
            scopes.bloquear(workspace).orElseThrow();
            return operacion.get();
        });
    }
}
