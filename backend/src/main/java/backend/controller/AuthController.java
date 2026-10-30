package backend.controller;

import backend.dto.LoginRequest;
import backend.dto.LoginResponse;
import backend.dto.RegisterRequest;
import backend.dto.UserDTO;
import backend.entity.User;
import backend.exception.BadRequestException;
import backend.exception.ResourceNotFoundException;
import backend.repository.UserRepository;
import backend.security.JwtService;
import backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentification par identifiants (username/password) : {@code POST /login} délègue la
 * vérification du mot de passe à Spring Security ({@link AuthenticationManager}, qui s'appuie sur
 * {@code AppUserDetailsService} + {@code PasswordEncoder}) puis émet un JWT ({@link JwtService}).
 * Un identifiant/mot de passe invalide lève {@code AuthenticationException}, gérée de façon uniforme
 * par {@code GlobalExceptionHandler} (401 JSON).
 *
 * <p>{@code POST /register} est la seule route publique de création de compte (route ajoutée à
 * {@code SecurityConfig.PUBLIC_ROUTES}) — le rôle est toujours forcé à CONSULTANT côté serveur,
 * {@link RegisterRequest} n'ayant volontairement aucun champ {@code role}. Toute autre création
 * d'utilisateur (ADMIN/SUPER_ADMIN) reste réservée à {@code POST /api/users} (protégé).
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private static final String DEFAULT_REGISTRATION_ROLE = "CONSULTANT";

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getUsername()));

        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(new LoginResponse(token, "Bearer", user.getUsername(), user.getRole()));
    }

    @PostMapping("/register")
    public ResponseEntity<UserDTO> register(@Valid @RequestBody RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BadRequestException("Les mots de passe ne correspondent pas.");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BadRequestException("Ce nom d'utilisateur est déjà pris.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Cet email est déjà utilisé.");
        }

        UserDTO created = userService.create(new UserDTO(null, request.getUsername(), request.getEmail(),
                request.getPassword(), DEFAULT_REGISTRATION_ROLE, null, true));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
