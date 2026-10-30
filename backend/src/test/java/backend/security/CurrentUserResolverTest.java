package backend.security;

import backend.entity.User;
import backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link SecurityContextHolder} est un simple ThreadLocal statique : le peupler directement dans
 * {@code @Test} (comme le fait {@link JwtAuthenticationFilter} en production) est suffisant, sans
 * framework de mock supplémentaire.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserResolver currentUserResolver;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolve_authenticatedKnownUser_returnsUser() {
        authenticateAs("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(7L, "ADMIN")));

        Optional<User> result = currentUserResolver.resolve();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(7L);
        assertThat(result.get().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void resolve_noAuthentication_returnsEmpty() {
        SecurityContextHolder.clearContext();

        assertThat(currentUserResolver.resolve()).isEmpty();
    }

    @Test
    void resolve_anonymousAuthentication_returnsEmpty() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(currentUserResolver.resolve()).isEmpty();
    }

    @Test
    void resolve_authenticatedButUserNotInDatabase_returnsEmpty() {
        authenticateAs("ghost");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(currentUserResolver.resolve()).isEmpty();
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSULTANT"))));
    }

    private static User user(Long id, String role) {
        return User.builder().id(id).username("u").email("u@biat.tn").password("x").role(role).build();
    }
}
