package bank.user.repository;

import bank.user.domain.Role;
import bank.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /** Id is the last key so page boundaries stay stable between requests. */
    Sort SEARCH_ORDER = Sort.by("lastName", "firstName", "id");

    Optional<User> findByEmail(String email);

    Optional<User> findByIdpSub(String idpSub);

    /** The users with these Cognito subs, in one query; unknown subs are skipped. */
    List<User> findByIdpSubIn(Collection<String> idpSubs);

    boolean existsByEmail(String email);

    /**
     * Returns one page of users, optionally narrowed by role and enabled
     * status. A null filter means "any".
     */
    default Page<User> search(Role role, Boolean enabled, int page, int size) {
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (role != null) {
            spec = spec.and((root, query, cb) -> role == Role.ADMIN
                    ? root.get("role").in(Role.ADMIN, Role.ROOT_ADMIN)
                    : cb.equal(root.get("role"), role));
        }
        if (enabled != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("enabled"), enabled));
        }
        return findAll(spec, PageRequest.of(page, size, SEARCH_ORDER));
    }
}
