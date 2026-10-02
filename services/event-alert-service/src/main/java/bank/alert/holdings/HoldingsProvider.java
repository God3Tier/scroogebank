package bank.alert.holdings;

public interface HoldingsProvider {

    HoldingsReadResult read(HoldingsReadRequest request);
}
