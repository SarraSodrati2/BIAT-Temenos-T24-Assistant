package backend.controller;

import backend.dto.ChatHistoryDTO;
import backend.service.ChatHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat-history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    @GetMapping
    public ResponseEntity<Page<ChatHistoryDTO>> findByChat(
            @RequestParam Long chatId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(chatHistoryService.findByChat(chatId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChatHistoryDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(chatHistoryService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ChatHistoryDTO> create(@Valid @RequestBody ChatHistoryDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatHistoryService.create(dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        chatHistoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
