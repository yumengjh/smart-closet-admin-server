package com.yumg.starter.modules.rbac.infrastructure;

import com.yumg.starter.entities.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, String> {
    Optional<Role> findByCode(String code);

    @EntityGraph(attributePaths = "permissions")
    @Query("select distinct role from Role role")
    List<Role> findAllWithPermissions();
}
