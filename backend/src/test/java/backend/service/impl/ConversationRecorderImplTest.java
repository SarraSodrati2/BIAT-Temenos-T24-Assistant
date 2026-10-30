package backend.service.impl;

import backend.dto.ChatDTO;
import backend.dto.ChatHistoryDTO;
import backend.dto.ConversationRecordingResult;
import backend.entity.User;
import backend.security.CurrentUserResolver;
import backend.service.ChatHistoryService;
import backend.service.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La résolution de l'utilisateur courant depuis le {@code SecurityContext} (JWT absent/invalide/
 * anonyme/introuvable) est testée séparément dans {@code CurrentUserResolverTest} — ici,
 * {@link CurrentUserResolver} est mocké directement.
 */
@ExtendWith(MockitoExtension.class)
class ConversationRecorderImplTest {

    @Mock
    private CurrentUserResolver currentUserResolver;
    @Mock
    private ChatService chatService;
    @Mock
    private ChatHistoryService chatHistoryService;

    @InjectMocks
    private ConversationRecorderImpl conversationRecorder;

    @Test
    void recordUserMessage_existingChat_reusesTheMostRecentOne_andSavesWithMessageTypeUser() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        ChatDTO existingChat = new ChatDTO(99L, "Ancienne conversation", null, null, 7L, null, null, null, null);
        when(chatService.findByUser(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existingChat)));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(555L, "Quelle table contient CIN ?", "USER", null, 99L));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("Quelle table contient CIN ?");

        assertThat(result.getChatId()).isEqualTo(99L);
        assertThat(result.getUserMessageId()).isEqualTo(555L);
        assertThat(result.isPersisted()).isTrue();
        assertThat(result.isAuthenticationRequired()).isFalse();
        verify(chatService, never()).create(any());
        ArgumentCaptor<ChatHistoryDTO> captor = ArgumentCaptor.forClass(ChatHistoryDTO.class);
        verify(chatHistoryService).create(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(99L);
        assertThat(captor.getValue().getContent()).isEqualTo("Quelle table contient CIN ?");
        assertThat(captor.getValue().getMessageType()).isEqualTo("USER");
    }

    @Test
    void recordUserMessage_noExistingChat_createsOne() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(3L)));
        when(chatService.findByUser(eq(3L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(chatService.create(any())).thenReturn(new ChatDTO(55L, "Résume CUSTOMER", null, null, 3L, null, null, null, null));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(777L, "Résume CUSTOMER", "USER", null, 55L));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("Résume CUSTOMER");

        assertThat(result.getChatId()).isEqualTo(55L);
        assertThat(result.getUserMessageId()).isEqualTo(777L);
        assertThat(result.isPersisted()).isTrue();
        assertThat(result.isAuthenticationRequired()).isFalse();
        ArgumentCaptor<ChatDTO> chatCaptor = ArgumentCaptor.forClass(ChatDTO.class);
        verify(chatService).create(chatCaptor.capture());
        assertThat(chatCaptor.getValue().getUserId()).isEqualTo(3L);
        assertThat(chatCaptor.getValue().getTitle()).isEqualTo("Résume CUSTOMER");
    }

    @Test
    void recordUserMessage_noAuthenticatedUser_flagsAuthenticationRequired_skipsPersistence() {
        when(currentUserResolver.resolve()).thenReturn(Optional.empty());

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("question");

        assertThat(result.isPersisted()).isFalse();
        assertThat(result.isAuthenticationRequired()).isTrue();
        verify(chatHistoryService, never()).create(any());
    }

    @Test
    void recordUserMessage_userResolutionThrows_isAnInternalErrorNotAnAuthenticationProblem() {
        // Une exception pendant la résolution de l'utilisateur (ex. Oracle indisponible) n'est pas la
        // même situation qu'une absence d'authentification : authenticationRequired doit rester faux
        // pour ne pas induire le frontend à demander une reconnexion qui ne résoudrait rien.
        when(currentUserResolver.resolve()).thenThrow(new RuntimeException("Oracle indisponible"));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("question");

        assertThat(result.isPersisted()).isFalse();
        assertThat(result.isAuthenticationRequired()).isFalse();
    }

    @Test
    void recordUserMessage_persistenceThrowsAfterAuthentication_isAnInternalErrorNotAnAuthenticationProblem() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findByUser(eq(7L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(chatService.create(any())).thenThrow(new RuntimeException("Oracle indisponible"));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("question");

        assertThat(result.isPersisted()).isFalse();
        assertThat(result.isAuthenticationRequired()).isFalse();
    }

    @Test
    void recordAssistantMessage_savesWithMessageTypeAssistant_andReturnsItsId() {
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(888L, "Voici la réponse.", "ASSISTANT", null, 99L));

        Long messageId = conversationRecorder.recordAssistantMessage(99L, "Voici la réponse.");

        assertThat(messageId).isEqualTo(888L);
        ArgumentCaptor<ChatHistoryDTO> captor = ArgumentCaptor.forClass(ChatHistoryDTO.class);
        verify(chatHistoryService).create(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(99L);
        assertThat(captor.getValue().getContent()).isEqualTo("Voici la réponse.");
        assertThat(captor.getValue().getMessageType()).isEqualTo("ASSISTANT");
    }

    @Test
    void recordAssistantMessage_persistenceThrows_isSwallowed_returnsNullInsteadOfPropagating() {
        when(chatHistoryService.create(any())).thenThrow(new RuntimeException("Oracle indisponible"));

        Long messageId = conversationRecorder.recordAssistantMessage(1L, "réponse");

        assertThat(messageId).isNull();
    }

    private static User user(Long id) {
        return User.builder().id(id).username("u").email("u@biat.tn").password("x").role("CONSULTANT").build();
    }

    // ---------------------------------------------------------------------------------------------
    // Conversation ciblée par le client + absence de doublon de question
    // ---------------------------------------------------------------------------------------------

    @Test
    void recordUserMessage_withRequestedChatOwnedByTheUser_usesThatChat_notTheMostRecentOne() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenReturn(new ChatDTO(12L, "Ancienne", null, null, 7L, null, null, null, null));
        when(chatHistoryService.findByChat(eq(12L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(600L, "q", "USER", null, 12L));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("q", 12L);

        assertThat(result.getChatId()).isEqualTo(12L);
        verify(chatService, never()).findByUser(any(), any(Pageable.class));
        verify(chatService, never()).create(any());
    }

    @Test
    void recordUserMessage_withRequestedChatOfAnotherUser_isIgnored_fallsBackToTheMostRecentChat() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenReturn(new ChatDTO(12L, "Autrui", null, null, 99L, null, null, null, null));
        when(chatService.findByUser(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new ChatDTO(30L, "Mien", null, null, 7L, null, null, null, null))));
        when(chatHistoryService.findByChat(eq(30L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(601L, "q", "USER", null, 30L));

        assertThat(conversationRecorder.recordUserMessage("q", 12L).getChatId()).isEqualTo(30L);
    }

    @Test
    void recordUserMessage_withUnreadableRequestedChat_fallsBackToTheMostRecentChat() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenThrow(new RuntimeException("Accès refusé"));
        when(chatService.findByUser(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new ChatDTO(30L, "Mien", null, null, 7L, null, null, null, null))));
        when(chatHistoryService.findByChat(eq(30L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(602L, "q", "USER", null, 30L));

        assertThat(conversationRecorder.recordUserMessage("q", 12L).getChatId()).isEqualTo(30L);
    }

    @Test
    void recordUserMessage_sameQuestionStillUnanswered_reusesTheExistingMessage_noDuplicateRow() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenReturn(new ChatDTO(12L, "Conv", null, null, 7L, null, null, null, null));
        when(chatHistoryService.findByChat(eq(12L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new ChatHistoryDTO(700L, "quel champ contient SWIFT ?", "USER", null, 12L))));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("  quel champ contient SWIFT ?  ", 12L);

        assertThat(result.getUserMessageId()).isEqualTo(700L);
        assertThat(result.getChatId()).isEqualTo(12L);
        verify(chatHistoryService, never()).create(any());
    }

    @Test
    void recordUserMessage_sameQuestionAlreadyAnswered_savesANewMessage() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenReturn(new ChatDTO(12L, "Conv", null, null, 7L, null, null, null, null));
        // Le dernier message est la RÉPONSE : reposer la même question est un nouvel échange légitime.
        when(chatHistoryService.findByChat(eq(12L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new ChatHistoryDTO(701L, "réponse", "ASSISTANT", null, 12L))));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(702L, "q", "USER", null, 12L));

        ConversationRecordingResult result = conversationRecorder.recordUserMessage("q", 12L);

        assertThat(result.getUserMessageId()).isEqualTo(702L);
        verify(chatHistoryService).create(any());
    }

    @Test
    void recordUserMessage_duplicateCheckFails_stillSavesTheQuestion() {
        when(currentUserResolver.resolve()).thenReturn(Optional.of(user(7L)));
        when(chatService.findById(12L)).thenReturn(new ChatDTO(12L, "Conv", null, null, 7L, null, null, null, null));
        when(chatHistoryService.findByChat(eq(12L), any(Pageable.class))).thenThrow(new RuntimeException("lecture impossible"));
        when(chatHistoryService.create(any())).thenReturn(new ChatHistoryDTO(703L, "q", "USER", null, 12L));

        assertThat(conversationRecorder.recordUserMessage("q", 12L).getUserMessageId()).isEqualTo(703L);
    }
}
