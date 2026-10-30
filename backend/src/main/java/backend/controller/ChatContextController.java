package backend.controller;

import backend.service.ContextBuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expose le contexte texte (construit depuis Oracle) destiné à être envoyé
 * plus tard à un modèle IA. Aucun appel LLM ici.
 */
@RestController
@RequestMapping("/api/chat/context")
@RequiredArgsConstructor
public class ChatContextController {

    private final ContextBuilderService contextBuilderService;

    @GetMapping(value = "/{nomTable}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getContext(@PathVariable String nomTable) {
        return ResponseEntity.ok(contextBuilderService.buildContext(nomTable));
    }

    @GetMapping(value = "/{nomTable}/{fieldName}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getFieldContext(@PathVariable String nomTable, @PathVariable String fieldName) {
        return ResponseEntity.ok(contextBuilderService.buildFieldContext(nomTable, fieldName));
    }
}
