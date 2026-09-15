package bank.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Root admin cannot be deleted or disabled. */
    @Column(nullable = false)
    @Setter
    private boolean deletable = true;

    /** Cognito subject identifier ("sub") for this user. */
    @Column(name = "idp_sub", nullable = false, unique = true)
    private String idpSub;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public User(UUID id, String firstName, String lastName, String email, Role role, String idpSub) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.role = role;
        this.idpSub = idpSub;
        this.enabled = true;
        this.deletable = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // Hand-written setters: each change also bumps updatedAt.

    public void setFirstName(String firstName) {
        this.firstName = firstName;
        touch();
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
        touch();
    }

    public void setEmail(String email) {
        this.email = email;
        touch();
    }

    public void setRole(Role role) {
        this.role = role;
        touch();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
