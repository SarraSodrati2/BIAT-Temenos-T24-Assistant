package backend.repository;

import backend.entity.ChatHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    Page<ChatHistory> findByChatId(Long chatId, Pageable pageable);

    long countByChatId(Long chatId);
}
