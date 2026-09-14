package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.EmailVerificationCode;
import com.metricol.api.enums.VerificationPurpose;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    /** El vigente es siempre el último emitido para ese correo y propósito. */
    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndConsumedFalseOrderByCreatedAtDesc(
            String email, VerificationPurpose purpose);

    /**
     * Da por usados todos los vigentes de ese correo y propósito. Se llama antes
     * de emitir uno nuevo, para que dos pantallas abiertas no dejen dos válidos.
     */
    @Modifying
    @Query("update EmailVerificationCode c set c.consumed = true "
            + "where c.email = :email and c.purpose = :purpose and c.consumed = false")
    void consumirTodos(@Param("email") String email, @Param("purpose") VerificationPurpose purpose);
}
