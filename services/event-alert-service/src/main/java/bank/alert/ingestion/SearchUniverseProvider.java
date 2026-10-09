package bank.alert.ingestion;

import java.util.List;

/**
 * Selects public instrument identifiers from holdings without exposing private
 * portfolio facts to the outbound search adapter.
 */
public interface SearchUniverseProvider {

    List<String> eligibleInstrumentIds();
}
