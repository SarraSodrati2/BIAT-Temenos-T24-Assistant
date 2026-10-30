package backend.controller;

import backend.dto.ChatContextUpdateDTO;
import backend.dto.ChatDTO;
import backend.service.ChatService;
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
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public ResponseEntity<Page<ChatDTO>> findByUser(
            @RequestParam Long userId,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(chatService.findByUser(userId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChatDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ChatDTO> create(@Valid @RequestBody ChatDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ChatDTO> update(@PathVariable Long id, @Valid @RequestBody ChatDTO dto) {
        return ResponseEntity.ok(chatService.update(id, dto));
    }

    /** Fixe/change le contexte T24 (module/table/champ) d'une conversation, voir le parcours à bulles de /chatbot. */
    @PutMapping("/{id}/context")
    public ResponseEntity<ChatDTO> updateContext(@PathVariable Long id, @Valid @RequestBody ChatContextUpdateDTO dto) {
        return ResponseEntity.ok(chatService.updateContext(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        chatService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
