package bank.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "instrument")
public class Instrument {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 32)
    private String ticker;

    @Column(name = "issuer_name", nullable = false, length = 200)
    private String issuerName;

    @Column(nullable = false, length = 100)
    private String sector;

    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    protected Instrument() {
    }

    public String getId() { return id; }
    public String getTicker() { return ticker; }
    public String getIssuerName() { return issuerName; }
    public String getSector() { return sector; }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
}
