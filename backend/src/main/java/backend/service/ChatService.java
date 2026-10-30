package backend.service;

import backend.dto.ChatContextUpdateDTO;
import backend.dto.ChatDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChatService {

    Page<ChatDTO> findByUser(Long userId, Pageable pageable);

    ChatDTO findById(Long id);

    ChatDTO create(ChatDTO dto);

    ChatDTO update(Long id, ChatDTO dto);

    ChatDTO updateContext(Long id, ChatContextUpdateDTO dto);

    void delete(Long id);
}
