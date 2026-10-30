package backend.service.impl;

import backend.dto.ChatDTO;
import backend.entity.Chat;
import backend.entity.User;
import backend.repository.ChatHistoryRepository;
import backend.repository.ChatRepository;
import backend.repository.UserRepository;
import backend.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Avant cette vérification, {@code findByUser(userId, ...)} faisait confiance au {@code userId}
 * fourni par le client sans jamais le comparer à l'utilisateur authentifié — n'importe quel
 * utilisateur connecté pouvait lire l'historique de n'importe qui d'autre en changeant ce paramètre.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ChatHistoryRepository chatHistoryRepository;
    @Mock
    private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private ChatServiceImpl chatService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 20);

    @Test
    void findByUser_ownUserId_isAllowed() {
        actingAs(42L, "CONSULTANT");
        when(chatRepository.findByUserId(eq(42L), any())).thenReturn(org.springframework.data.domain.Page.empty());

        chatService.findByUser(42L, PAGEABLE);

        verify(chatRepository).findByUserId(42L, PAGEABLE);
    }

    @Test
    void findByUser_someoneElsesUserId_asConsultant_isDenied() {
        actingAs(42L, "CONSULTANT");

        assertThatThrownBy(() -> chatService.findByUser(99L, PAGEABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findByUser_someoneElsesUserId_asAdmin_isDenied() {
        // Seul SUPER_ADMIN a le droit explicite de "consultation de toutes les conversations".
        actingAs(42L, "ADMIN");

        assertThatThrownBy(() -> chatService.findByUser(99L, PAGEABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findByUser_someoneElsesUserId_asSuperAdmin_isAllowed() {
        actingAs(1L, "SUPER_ADMIN");
        when(chatRepository.findByUserId(eq(99L), any())).thenReturn(org.springframework.data.domain.Page.empty());

        chatService.findByUser(99L, PAGEABLE);

        verify(chatRepository).findByUserId(99L, PAGEABLE);
    }

    @Test
    void findByUser_noAuthenticatedUser_isDenied() {
        when(currentUserResolver.resolve()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.findByUser(1L, PAGEABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findById_ownChat_isAllowed() {
        actingAs(42L, "CONSULTANT");
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chatOwnedBy(42L)));

        ChatDTO result = chatService.findById(100L);

        assertThat(result.getId()).isEqualTo(100L);
    }

    @Test
    void findById_someoneElsesChat_asConsultant_isDenied() {
        actingAs(99L, "CONSULTANT");
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chatOwnedBy(42L)));

        assertThatThrownBy(() -> chatService.findById(100L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_someoneElsesChat_asConsultant_isDenied() {
        actingAs(99L, "CONSULTANT");
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chatOwnedBy(42L)));

        assertThatThrownBy(() -> chatService.update(100L, new ChatDTO(100L, "Nouveau titre", null, null, 42L, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_ownChat_isAllowed() {
        actingAs(42L, "CONSULTANT");
        Chat chat = chatOwnedBy(42L);
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chat));
        when(chatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatDTO result = chatService.update(100L, new ChatDTO(100L, "Nouveau titre", null, null, 42L, null, null, null, null));

        assertThat(result.getTitle()).isEqualTo("Nouveau titre");
    }

    @Test
    void delete_someoneElsesChat_asConsultant_isDenied() {
        actingAs(99L, "CONSULTANT");
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chatOwnedBy(42L)));

        assertThatThrownBy(() -> chatService.delete(100L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_ownChat_isAllowed() {
        actingAs(42L, "CONSULTANT");
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chatOwnedBy(42L)));

        chatService.delete(100L);

        verify(chatRepository).delete(any());
    }

    @Test
    void create_forSomeoneElse_asConsultant_isDenied() {
        actingAs(42L, "CONSULTANT");

        assertThatThrownBy(() -> chatService.create(new ChatDTO(null, "t", null, null, 99L, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_forSelf_isAllowed() {
        actingAs(42L, "CONSULTANT");
        User owner = User.builder().id(42L).username("u").email("u@biat.tn").password("x").role("CONSULTANT").build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(owner));
        when(chatRepository.save(any())).thenAnswer(inv -> {
            Chat saved = inv.getArgument(0);
            saved.setId(200L);
            return saved;
        });

        ChatDTO result = chatService.create(new ChatDTO(null, "t", null, null, 42L, null, null, null, null));

        assertThat(result.getId()).isEqualTo(200L);
    }

    @Test
    void create_forAnyone_asSuperAdmin_isAllowed() {
        actingAs(1L, "SUPER_ADMIN");
        User owner = User.builder().id(99L).username("u").email("u@biat.tn").password("x").role("CONSULTANT").build();
        when(userRepository.findById(99L)).thenReturn(Optional.of(owner));
        when(chatRepository.save(any())).thenAnswer(inv -> {
            Chat saved = inv.getArgument(0);
            saved.setId(200L);
            return saved;
        });

        chatService.create(new ChatDTO(null, "t", null, null, 99L, null, null, null, null));

        verify(chatRepository).save(any());
    }

    private static Chat chatOwnedBy(Long userId) {
        User owner = User.builder().id(userId).username("u").email("u@biat.tn").password("x").role("CONSULTANT").build();
        return Chat.builder().id(100L).title("Ancienne conversation").user(owner).build();
    }

    private void actingAs(Long id, String role) {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(
                User.builder().id(id).username("u").email("u@biat.tn").password("x").role(role).build()));
    }
}
