package backend.controller;

import backend.dto.ChatAskRequest;
import backend.dto.ChatAskResponse;
import backend.service.ChatbotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/ask")
    public ResponseEntity<ChatAskResponse> ask(@Valid @RequestBody ChatAskRequest request) {
        Long chatId = request.getChatId();
        return ResponseEntity.ok(chatId == null
                ? chatbotService.ask(request.getQuestion())
                : chatbotService.ask(request.getQuestion(), chatId));
    }
}
    