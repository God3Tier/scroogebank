package bank.engagement.calendar;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {

    /** Every query names the owner: another agent's event is simply not found. */
    Optional<CalendarEvent> findByIdAndOwnerAgentSub(UUID id, String ownerAgentSub);

    @Query("""
            SELECT e FROM CalendarEvent e
             WHERE e.ownerAgentSub = :owner
               AND e.startsAt < :to AND e.endsAt > :from
               AND (:includeCancelled = true OR e.status = bank.engagement.calendar.EventStatus.SCHEDULED)
             ORDER BY e.startsAt, e.id
            """)
    List<CalendarEvent> findOverlapping(@Param("owner") String owner, @Param("from") Instant from,
            @Param("to") Instant to, @Param("includeCancelled") boolean includeCancelled, Pageable page);

    @Query("""
            SELECT max(e.startsAt) FROM CalendarEvent e
             WHERE e.ownerAgentSub = :owner AND e.clientId = :clientId
               AND e.status = bank.engagement.calendar.EventStatus.SCHEDULED
               AND e.startsAt < :before
            """)
    Optional<Instant> findLastStart(@Param("owner") String owner, @Param("clientId") UUID clientId,
            @Param("before") Instant before);
}
