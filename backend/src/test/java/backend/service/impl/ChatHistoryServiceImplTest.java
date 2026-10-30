package backend.service.impl;

import backend.entity.Chat;
import backend.entity.ChatHistory;
import backend.entity.User;
import backend.repository.ChatHistoryRepository;
import backend.repository.ChatRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Même règle d'appartenance que {@code ChatServiceImplTest}, ici via le propriétaire du Chat résolu par chatId. */
@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceImplTest {

    @Mock
    private ChatHistoryRepository chatHistoryRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private ChatHistoryServiceImpl chatHistoryService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 20);

    @Test
    void findByChat_ownChat_isAllowed() {
        User owner = user(42L, "CONSULTANT");
        Chat chat = Chat.builder().id(100L).title("t").user(owner).build();
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chat));
        actingAs(owner);
        when(chatHistoryRepository.findByChatId(eq(100L), any())).thenReturn(org.springframework.data.domain.Page.empty());

        chatHistoryService.findByChat(100L, PAGEABLE);

        verify(chatHistoryRepository).findByChatId(100L, PAGEABLE);
    }

    @Test
    void findByChat_someoneElsesChat_asConsultant_isDenied() {
        Chat chat = Chat.builder().id(100L).title("t").user(user(42L, "CONSULTANT")).build();
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chat));
        actingAs(user(99L, "CONSULTANT"));

        assertThatThrownBy(() -> chatHistoryService.findByChat(100L, PAGEABLE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findByChat_someoneElsesChat_asSuperAdmin_isAllowed() {
        Chat chat = Chat.builder().id(100L).title("t").user(user(42L, "CONSULTANT")).build();
        when(chatRepository.findById(100L)).thenReturn(Optional.of(chat));
        actingAs(user(1L, "SUPER_ADMIN"));
        when(chatHistoryRepository.findByChatId(eq(100L), any())).thenReturn(org.springframework.data.domain.Page.empty());

        chatHistoryService.findByChat(100L, PAGEABLE);

        verify(chatHistoryRepository).findByChatId(100L, PAGEABLE);
    }

    @Test
    void delete_ownMessage_isAllowed() {
        User owner = user(42L, "CONSULTANT");
        Chat chat = Chat.builder().id(100L).title("t").user(owner).build();
        ChatHistory history = ChatHistory.builder().id(1L).content("c").messageType("USER").chat(chat).build();
        when(chatHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
        actingAs(owner);

        chatHistoryService.delete(1L);

        verify(chatHistoryRepository).delete(history);
    }

    @Test
    void delete_someoneElsesMessage_asConsultant_isDenied() {
        Chat chat = Chat.builder().id(100L).title("t").user(user(42L, "CONSULTANT")).build();
        ChatHistory history = ChatHistory.builder().id(1L).content("c").messageType("USER").chat(chat).build();
        when(chatHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
        actingAs(user(99L, "CONSULTANT"));

        assertThatThrownBy(() -> chatHistoryService.delete(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_someoneElsesMessage_asSuperAdmin_isAllowed() {
        Chat chat = Chat.builder().id(100L).title("t").user(user(42L, "CONSULTANT")).build();
        ChatHistory history = ChatHistory.builder().id(1L).content("c").messageType("USER").chat(chat).build();
        when(chatHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
        actingAs(user(1L, "SUPER_ADMIN"));

        chatHistoryService.delete(1L);

        verify(chatHistoryRepository).delete(history);
    }

    private void actingAs(User user) {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user));
    }

    private static User user(Long id, String role) {
        return User.builder().id(id).username("u" + id).email("u" + id + "@biat.tn").password("x").role(role).build();
    }
}
