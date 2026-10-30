package backend.service;

import backend.dto.ChatHistoryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatHistoryService {

    Page<ChatHistoryDTO> findByChat(Long chatId, Pageable pageable);

    ChatHistoryDTO findById(Long id);

    ChatHistoryDTO create(ChatHistoryDTO dto);

    void delete(Long id);
}
