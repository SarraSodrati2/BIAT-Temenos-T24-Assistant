package backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryDTO {

    private Long id;

    @NotBlank
    @Size(max = 4000)
    private String content;

    /** USER ou ASSISTANT */
    @NotBlank
    @Size(max = 20)
    private String messageType;

    private LocalDateTime createdAt;

    @NotNull
    private Long chatId;
}
