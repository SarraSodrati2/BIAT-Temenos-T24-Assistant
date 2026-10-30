package backend.service;

import backend.dto.UserDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    Page<UserDTO> findAll(Pageable pageable);

    UserDTO findById(Long id);

    UserDTO create(UserDTO dto);

    UserDTO update(Long id, UserDTO dto);

    void delete(Long id);
}
