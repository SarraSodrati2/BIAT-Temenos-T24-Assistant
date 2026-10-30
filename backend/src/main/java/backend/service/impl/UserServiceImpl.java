package backend.service.impl;

import backend.dto.UserDTO;
import backend.entity.User;
import backend.exception.ResourceNotFoundException;
import backend.repository.UserRepository;
import backend.security.CurrentUserResolver;
import backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@code SecurityConfig} réserve déjà l'écriture (POST/PUT/DELETE /api/users/**) à ADMIN/SUPER_ADMIN
 * au niveau route, mais ne peut pas exprimer la hiérarchie plus fine demandée : un ADMIN ne peut
 * créer/modifier/supprimer que des CONSULTANT (jamais un autre ADMIN ni un SUPER_ADMIN), alors qu'un
 * SUPER_ADMIN n'a aucune restriction. {@link #assertAdminCanManage} applique cette règle ici, seul
 * endroit où le rôle de l'acteur ET du destinataire sont tous les deux connus.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final String ROLE_CONSULTANT = "CONSULTANT";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserResolver currentUserResolver;

    @Override
    public Page<UserDTO> findAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toDto);
    }

    @Override
    public UserDTO findById(Long id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional
    public UserDTO create(UserDTO dto) {
        assertAdminCanManage(null, dto.getRole());

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRole(dto.getRole());
        user.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);
        return toDto(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO update(Long id, UserDTO dto) {
        User user = getEntity(id);
        assertAdminCanManage(user.getRole(), dto.getRole());

        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
        user.setRole(dto.getRole());
        if (dto.getIsActive() != null) {
            user.setIsActive(dto.getIsActive());
        }
        return toDto(userRepository.save(user));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        User user = getEntity(id);
        assertAdminCanManage(user.getRole(), null);
        userRepository.delete(user);
    }

    /**
     * Un ADMIN ne peut agir que si la cible est (et reste) CONSULTANT ; un SUPER_ADMIN n'est jamais
     * restreint. {@code targetCurrentRole} est {@code null} à la création (pas encore de cible
     * existante) ; {@code targetNewRole} est {@code null} à la suppression (pas de nouveau rôle).
     *
     * <p>Aucun acteur authentifié n'est autorisé UNIQUEMENT pour l'auto-inscription publique
     * ({@code POST /register}, hors JWT par construction) créant un nouveau CONSULTANT — jamais pour
     * modifier/supprimer un compte existant, ni créer un ADMIN/SUPER_ADMIN. {@code POST /api/users}
     * (le seul autre appelant de {@code create}) est de toute façon bloqué avant d'arriver ici par
     * {@code SecurityConfig} ({@code hasAnyRole("ADMIN","SUPER_ADMIN")}) : un appel non authentifié
     * ne peut donc provenir que de {@code /register}, qui force déjà ce rôle côté {@code AuthController}.
     */
    private void assertAdminCanManage(String targetCurrentRole, String targetNewRole) {
        Optional<User> actor = currentUserResolver.resolve();
        if (actor.isEmpty()) {
            if (targetCurrentRole == null && ROLE_CONSULTANT.equals(targetNewRole)) {
                return;
            }
            throw new AccessDeniedException("Authentification requise.");
        }
        if (ROLE_SUPER_ADMIN.equals(actor.get().getRole())) {
            return;
        }
        boolean currentRoleAllowed = targetCurrentRole == null || ROLE_CONSULTANT.equals(targetCurrentRole);
        boolean newRoleAllowed = targetNewRole == null || ROLE_CONSULTANT.equals(targetNewRole);
        if (!currentRoleAllowed || !newRoleAllowed) {
            throw new AccessDeniedException("Un ADMIN ne peut gérer que des comptes CONSULTANT.");
        }
    }

    private User getEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private UserDTO toDto(User user) {
        return new UserDTO(user.getId(), user.getUsername(), user.getEmail(), null,
                user.getRole(), user.getCreatedAt(), user.getIsActive());
    }
}
