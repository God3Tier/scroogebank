package bank.alert.ingestion;

import java.util.List;

public interface PublicSearchProvider {
    List<PublicSearchResult> search(PublicSearchRequest request);
}
