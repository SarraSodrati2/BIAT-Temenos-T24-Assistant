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
public class TableT24DTO {

    private Long id;

    @NotBlank
    @Size(max = 255)
    private String nomTable;

    @NotBlank
    private String data;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @NotNull
    private Long moduleId;
}
