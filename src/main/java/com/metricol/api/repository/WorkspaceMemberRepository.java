package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.WorkspaceMember;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

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
}
