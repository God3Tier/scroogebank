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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
    @Setter
    private String firstName;

    @Column(nullable = false)
    @Setter
    private String lastName;

    @Column(nullable = false, unique = true)
    @Setter
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Setter
    private Role role;

    @Column(nullable = false)
    @Setter
    private boolean enabled = true;

    /** Root admin cannot be deleted or disabled. */
    @Column(nullable = false)
    @Setter
    private boolean deletable = true;

    /** Cognito subject identifier ("sub") for this user. */
    @Column(name = "idp_sub", nullable = false, unique = true)
    private String idpSub;

    /** Set by Hibernate on INSERT. */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Set by Hibernate on INSERT and on each flush that writes an UPDATE. */
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public User(UUID id, String firstName, String lastName, String email, Role role, String idpSub) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.role = role;
        this.idpSub = idpSub;
    }
}
