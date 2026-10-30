package com.yumg.starter.modules.navigation.infrastructure;

import com.yumg.starter.entities.NavigationMenu;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NavigationMenuRepository extends JpaRepository<NavigationMenu, String> {
    List<NavigationMenu> findAllByOrderBySortOrderAscCodeAsc();
    Optional<NavigationMenu> findByCode(String code);
    boolean existsByParentId(String parentId);

    /**
     * Row-locks the whole menu table in a fixed order. Structural changes take this lock first so
     * two concurrent moves of different menus cannot both pass the cycle check (#169).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from NavigationMenu m order by m.id")
    List<NavigationMenu> lockAllForStructureChange();
}
