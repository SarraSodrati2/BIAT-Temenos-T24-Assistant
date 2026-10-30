package backend.service.impl;

import backend.dto.UserDTO;
import backend.entity.User;
import backend.repository.UserRepository;
import backend.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code SecurityConfig} réserve déjà l'écriture sur /api/users/** à ADMIN/SUPER_ADMIN au niveau
 * route, mais ne peut pas exprimer "un ADMIN ne gère que des CONSULTANT" : c'est cette hiérarchie
 * plus fine, entièrement dans {@code UserServiceImpl}, que ces tests couvrent.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void create_asSuperAdmin_canCreateAnyRole() {
        actingAs("SUPER_ADMIN");
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        UserDTO result = userService.create(new UserDTO(null, "bob", "bob@biat.tn", "pw12345678", "ADMIN", null, true));

        assertThat(result.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void create_asAdmin_creatingConsultant_isAllowed() {
        actingAs("ADMIN");
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        UserDTO result = userService.create(new UserDTO(null, "carl", "carl@biat.tn", "pw12345678", "CONSULTANT", null, true));

        assertThat(result.getRole()).isEqualTo("CONSULTANT");
    }

    @Test
    void create_asAdmin_creatingAnotherAdmin_isDenied() {
        actingAs("ADMIN");

        assertThatThrownBy(() -> userService.create(
                new UserDTO(null, "dave", "dave@biat.tn", "pw12345678", "ADMIN", null, true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Régression réelle trouvée en lançant l'application : AuthController.register() (route
     * publique, sans JWT) appelle ce même UserService.create() pour l'auto-inscription — sans ce
     * cas, toute inscription échouait avec 403 "Authentification requise.", alors que /register
     * force déjà le rôle à CONSULTANT et n'est jamais accessible sans passer par cette route.
     */
    @Test
    void create_noAuthenticatedActor_creatingConsultant_isAllowed_selfRegistration() {
        when(currentUserResolver.resolve()).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        UserDTO result = userService.create(
                new UserDTO(null, "newbie", "newbie@biat.tn", "pw12345678", "CONSULTANT", null, true));

        assertThat(result.getRole()).isEqualTo("CONSULTANT");
    }

    @Test
    void create_noAuthenticatedActor_creatingAdmin_isDenied() {
        when(currentUserResolver.resolve()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(
                new UserDTO(null, "sneaky", "sneaky@biat.tn", "pw12345678", "ADMIN", null, true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_asAdmin_onExistingAdmin_isDenied_evenIfNewRoleIsConsultant() {
        actingAs("ADMIN");
        User target = User.builder().id(5L).username("eve").email("eve@biat.tn").password("x").role("ADMIN").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> userService.update(5L,
                new UserDTO(5L, "eve", "eve@biat.tn", null, "CONSULTANT", null, true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_asAdmin_tryingToPromoteConsultantToAdmin_isDenied() {
        actingAs("ADMIN");
        User target = User.builder().id(6L).username("frank").email("frank@biat.tn").password("x").role("CONSULTANT").build();
        when(userRepository.findById(6L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> userService.update(6L,
                new UserDTO(6L, "frank", "frank@biat.tn", null, "ADMIN", null, true)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_asAdmin_onExistingConsultant_isAllowed() {
        actingAs("ADMIN");
        User target = User.builder().id(7L).username("grace").email("grace@biat.tn").password("x").role("CONSULTANT").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserDTO result = userService.update(7L,
                new UserDTO(7L, "grace", "grace2@biat.tn", null, "CONSULTANT", null, true));

        assertThat(result.getEmail()).isEqualTo("grace2@biat.tn");
    }

    @Test
    void delete_asAdmin_onExistingAdmin_isDenied() {
        actingAs("ADMIN");
        User target = User.builder().id(8L).username("henry").email("henry@biat.tn").password("x").role("ADMIN").build();
        when(userRepository.findById(8L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> userService.delete(8L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_asAdmin_onExistingConsultant_isAllowed() {
        actingAs("ADMIN");
        User target = User.builder().id(9L).username("iris").email("iris@biat.tn").password("x").role("CONSULTANT").build();
        when(userRepository.findById(9L)).thenReturn(Optional.of(target));

        userService.delete(9L);
    }

    private void actingAs(String role) {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(
                User.builder().id(1L).username("actor").email("actor@biat.tn").password("x").role(role).build()));
    }
}
