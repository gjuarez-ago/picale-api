package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.WorkspaceMember;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /**
     * Quita el CHECK que la base puso sobre los valores del rol.
     *
     * <p>La tabla nació cuando {@code Role} solo tenía {@code ADMIN}, y
     * Hibernate genera {@code check (role in ('ADMIN'))} al crearla; con
     * {@code ddl-auto=update} no lo vuelve a tocar. Después se añadieron
     * {@code EDITOR} y {@code VIEWER}: compila, pasa las pruebas de una base
     * nueva, y en una base que ya existía falla al escribir —al aceptar una
     * invitación como editor, o al dar acceso a alguien— con
     * {@code violates check constraint workspace_members_role_check}.
     *
     * <p>Mismo caso, y misma solución, que
     * {@code MediaAssetRepository.quitarCheckDeEstado}: quien valida el rol es
     * el enum de Java, único sitio por donde se escribe. {@code if exists}
     * porque corre en cada arranque.
     */
    @Modifying
    @Transactional
    @Query(value = "alter table workspace_members drop constraint if exists workspace_members_role_check",
            nativeQuery = true)
    void quitarCheckDeRol();

    /**
     * La única comprobación que separa a un cliente de otro al cambiar de
     * workspace. Sin esta fila, conocer el id de un workspace no da nada.
     */
    boolean existsByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Los workspaces de un usuario, en el orden en que se le fueron dando. */
    @Query("""
            select m from WorkspaceMember m
            join fetch m.workspace
            where m.user.id = :userId
            order by m.createdAt
            """)
    List<WorkspaceMember> findDelUsuario(@Param("userId") UUID userId);

    /** La membresía de una persona en un workspace, para leer o cambiar sus permisos. */
    Optional<WorkspaceMember> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** El equipo de un workspace, con su gente, para la pantalla de administración. */
    @Query("""
            select m from WorkspaceMember m
            join fetch m.user
            where m.workspace.id = :workspaceId
            order by m.createdAt
            """)
    List<WorkspaceMember> findDelWorkspace(@Param("workspaceId") UUID workspaceId);

    /**
     * Cuántos administradores quedan. Se pregunta antes de quitar a alguien o
     * de bajarle el rol: un workspace sin ningún administrador no lo puede
     * recuperar nadie desde la aplicación.
     */
    long countByWorkspaceIdAndRole(UUID workspaceId, com.metricol.api.enums.Role role);
}